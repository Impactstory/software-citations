package org.grobid.core.engines;

import org.apache.commons.io.FileUtils;
import org.grobid.core.GrobidModels;
import org.grobid.core.analyzers.SoftwareAnalyzer;
import org.grobid.core.data.SoftwareComponent;
import org.grobid.core.data.BiblioComponent;
import org.grobid.core.data.SoftwareEntity;
import org.grobid.core.data.BiblioItem;
import org.grobid.core.data.BibDataSet;
import org.grobid.core.document.Document;
import org.grobid.core.document.DocumentPiece;
import org.grobid.core.document.DocumentSource;
import org.grobid.core.document.TEIFormatter;
import org.grobid.core.document.xml.XmlBuilderUtils;
import org.grobid.core.engines.config.GrobidAnalysisConfig;
import org.grobid.core.engines.label.SoftwareTaggingLabels;
import org.grobid.core.engines.label.SegmentationLabels;
import org.grobid.core.engines.label.TaggingLabel;
import org.grobid.core.engines.label.TaggingLabels;
import org.grobid.core.engines.tagging.GrobidCRFEngine;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.factory.GrobidFactory;
import org.grobid.core.features.FeaturesVectorSoftware;
import org.grobid.core.layout.BoundingBox;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.layout.LayoutTokenization;
import org.grobid.core.lexicon.SoftwareLexicon;
import org.grobid.core.sax.TextChunkSaxHandler;
import org.grobid.core.tokenization.TaggingTokenCluster;
import org.grobid.core.tokenization.TaggingTokenClusteror;
import org.grobid.core.utilities.*;
import org.grobid.core.utilities.counters.CntManager;
import org.grobid.core.utilities.counters.impl.CntManagerFactory;
import org.grobid.core.lexicon.FastMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.xml.sax.InputSource;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import nu.xom.Attribute;
import nu.xom.Element;
import nu.xom.Node;
import nu.xom.Text;

import static org.apache.commons.lang3.StringUtils.*;
import static org.grobid.core.document.xml.XmlBuilderUtils.teiElement;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Software mentions extraction.
 *
 * @author Patrice
 */
public class SoftwareParser extends AbstractParser {
    private static final Logger logger = LoggerFactory.getLogger(SoftwareParser.class);

    private static volatile SoftwareParser instance;

    public static SoftwareParser getInstance() {
        if (instance == null) {
            getNewInstance();
        }
        return instance;
    }

    /**
     * Create a new instance.
     */
    private static synchronized void getNewInstance() {
        instance = new SoftwareParser();
    }

    private SoftwareLexicon softwareLexicon = null;
	private EngineParsers parsers;
    private SoftwareDisambiguator disambiguator;

    private SoftwareParser() {
        super(GrobidModels.SOFTWARE, CntManagerFactory.getCntManager(), 
            GrobidCRFEngine.valueOf(SoftwareProperties.get("grobid.software.engine").toUpperCase()));

        softwareLexicon = SoftwareLexicon.getInstance();
		parsers = new EngineParsers();
        disambiguator = SoftwareDisambiguator.getInstance();
    }

    /**
     * Extract all Software mentions from a simple piece of text.
     */
    public List<SoftwareEntity> processText(String text, boolean disambiguate) throws Exception {
        if (isBlank(text)) {
            return null;
        }
        text = UnicodeUtil.normaliseText(text);
        List<SoftwareComponent> components = new ArrayList<SoftwareComponent>();
        List<SoftwareEntity> entities = null;
        try {
            text = text.replace("\n", " ");
            text = text.replace("\t", " ");
            List<LayoutToken> tokens = SoftwareAnalyzer.getInstance().tokenizeWithLayoutToken(text);
            if (tokens.size() == 0) {
                return null;
            }

            // to store software name positions (names coming from the optional dictionary)
            List<OffsetPosition> softwareTokenPositions = softwareLexicon.tokenPositionsSoftwareNames(tokens);
            String ress = addFeatures(tokens, softwareTokenPositions);
            String res;
            try {
                res = label(ress);
            } catch (Exception e) {
                throw new GrobidException("CRF labeling for software parsing failed.", e);
            }

            components = extractSoftwareComponents(text, res, tokens);

            // we group the identified components by full entities
            entities = groupByEntities(components);

            // disambiguation
            if (disambiguate)
                entities = disambiguator.disambiguate(entities, tokens);

            // propagate
            // we prepare a matcher for all the identified software names 
            FastMatcher termPattern = prepareTermPattern(entities);
            // we prepare the frequencies for each software name in the whole document
            Map<String, Integer> frequencies = prepareFrequencies(entities, tokens);
            // we prepare a map for mapping a software name with its positions of annotation in the document and its IDF
            Map<String, Pair<List<OffsetPosition>,Double>> termProfiles = prepareTermProfiles(entities);
            // and call the propagation method
            entities = propagateLayoutTokenSequence(tokens, entities, termProfiles, termPattern, frequencies);
            Collections.sort(entities);
        } catch (Exception e) {
            throw new GrobidException("An exception occured while running Grobid.", e);
        }

        return entities;
    }

	/**
	  * Extract all Software mentions from a pdf file 
	  */
    public Pair<List<SoftwareEntity>,Document> processPDF(File file, boolean disambiguate) throws IOException {

        List<SoftwareEntity> entities = new ArrayList<SoftwareEntity>();
        Document doc = null;
        try {
            GrobidAnalysisConfig config =
                        GrobidAnalysisConfig.builder()
                                .consolidateHeader(0)
                                .consolidateCitations(1)
                                .build();

			DocumentSource documentSource = 
				DocumentSource.fromPdf(file, config.getStartPage(), config.getEndPage());
			doc = parsers.getSegmentationParser().processing(documentSource, config);

            // process bibliographical reference section first
            List<BibDataSet> resCitations = parsers.getCitationParser().
                processingReferenceSection(doc, parsers.getReferenceSegmenterParser(), config.getConsolidateCitations());

            doc.setBibDataSets(resCitations);

            // here we process the relevant textual content of the document

            // for refining the process based on structures, we need to filter
            // segment of interest (e.g. header, body, annex) and possibly apply 
            // the corresponding model to further filter by structure types 

            // from the header, we are interested in title, abstract and keywords
            SortedSet<DocumentPiece> documentParts = doc.getDocumentPart(SegmentationLabels.HEADER);
            BiblioItem resHeader = null;
            if (documentParts != null) {
                Pair<String,List<LayoutToken>> headerFeatured = parsers.getHeaderParser().getSectionHeaderFeatured(doc, documentParts, true);
                String header = headerFeatured.getLeft();
                List<LayoutToken> tokenizationHeader = doc.getTokenizationParts(documentParts, doc.getTokenizations());
                String labeledResult = null;
                if ((header != null) && (header.trim().length() > 0)) {
                    labeledResult = parsers.getHeaderParser().label(header);

                    resHeader = new BiblioItem();
                    //parsers.getHeaderParser().processingHeaderSection(false, doc, resHeader);
                    resHeader.generalResultMapping(doc, labeledResult, tokenizationHeader);

                    // title
                    List<LayoutToken> titleTokens = resHeader.getLayoutTokens(TaggingLabels.HEADER_TITLE);
                    if (titleTokens != null) {
                        processLayoutTokenSequence(titleTokens, entities, disambiguate);
                    } 

                    // abstract
                    List<LayoutToken> abstractTokens = resHeader.getLayoutTokens(TaggingLabels.HEADER_ABSTRACT);
                    if (abstractTokens != null) {
                        processLayoutTokenSequence(abstractTokens, entities, disambiguate);
                    } 

                    // keywords
                    List<LayoutToken> keywordTokens = resHeader.getLayoutTokens(TaggingLabels.HEADER_KEYWORD);
                    if (keywordTokens != null) {
                        processLayoutTokenSequence(keywordTokens, entities, disambiguate);
                    }
                }
            }

            // process selected structures in the body,
            documentParts = doc.getDocumentPart(SegmentationLabels.BODY);
            List<TaggingTokenCluster> bodyClusters = null;
            if (documentParts != null) {
                // full text processing
                Pair<String, LayoutTokenization> featSeg = parsers.getFullTextParser().getBodyTextFeatured(doc, documentParts);
                if (featSeg != null) {
                    // if featSeg is null, it usually means that no body segment is found in the
                    // document segmentation
                    String bodytext = featSeg.getLeft();

                    LayoutTokenization tokenizationBody = featSeg.getRight();
                    String rese = null;
                    if ( (bodytext != null) && (bodytext.trim().length() > 0) ) {               
                        rese = parsers.getFullTextParser().label(bodytext);
                    } else {
                        logger.debug("Fulltext model: The input to the CRF processing is empty");
                    }

                    TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULLTEXT, rese, 
                        tokenizationBody.getTokenization(), true);
                    bodyClusters = clusteror.cluster();
                    for (TaggingTokenCluster cluster : bodyClusters) {
                        if (cluster == null) {
                            continue;
                        }

                        TaggingLabel clusterLabel = cluster.getTaggingLabel();

                        List<LayoutToken> localTokenization = cluster.concatTokens();
                        if ((localTokenization == null) || (localTokenization.size() == 0))
                            continue;

                        //String clusterContent = LayoutTokensUtil.normalizeText(LayoutTokensUtil.toText(cluster.concatTokens()));
                        if (clusterLabel.equals(TaggingLabels.PARAGRAPH) || clusterLabel.equals(TaggingLabels.ITEM)) {
                            //|| clusterLabel.equals(TaggingLabels.SECTION) {
                            processLayoutTokenSequence(localTokenization, entities, disambiguate);
                        } else if (clusterLabel.equals(TaggingLabels.TABLE)) {
                            //processLayoutTokenSequenceTableFigure(localTokenization, entities);
                        } else if (clusterLabel.equals(TaggingLabels.FIGURE)) {
                            //processLayoutTokenSequenceTableFigure(localTokenization, entities);
                        }
                    }
                }
            }

            // we don't process references (although reference titles could be relevant)
            // acknowledgement? 

            // we can process annexes
            /*documentParts = doc.getDocumentPart(SegmentationLabels.ANNEX);
            if (documentParts != null) {
                processDocumentPart(documentParts, doc, entities);
            }*/

            // footnotes are also relevant?
            /*documentParts = doc.getDocumentPart(SegmentationLabel.FOOTNOTE);
            if (documentParts != null) {
                processDocumentPart(documentParts, doc, components);
            }*/

            // propagate the disambiguated entities to the non-disambiguated entities corresponding to the same software name
            for(SoftwareEntity entity1 : entities) {
                if (entity1.getSoftwareName() != null && entity1.getSoftwareName().getWikidataId() != null) {
                    for (SoftwareEntity entity2 : entities) {
                        if (entity2.getSoftwareName() != null && entity2.getSoftwareName().getWikidataId() != null) {
                            // if the entity is already disdambiguated, nothing possible
                            continue;
                        }
                        if (entity2.getSoftwareName() != null && 
                            entity2.getSoftwareName().getRawForm().equals(entity1.getSoftwareName().getRawForm())) {
                            entity1.getSoftwareName().copyKnowledgeInformationTo(entity2.getSoftwareName());
                            entity2.getSoftwareName().setLang(entity1.getSoftwareName().getLang());
                        }
                    }
                }
            }

            // second pass for document level consistency: the goal is to propagate the identified entities in the part of the
            // document where the same term appears without labeling. For controlling the propagation we use a tf-idf measure
            // of the term. As possible improvement, a specific classifier could be used.   

            // we prepare a matcher for all the identified software names 
            FastMatcher termPattern = prepareTermPattern(entities);
            // we prepare the frequencies for each software name in the whole document
            Map<String, Integer> frequencies = prepareFrequencies(entities, doc.getTokenizations());
            // we prepare a map for mapping a software name with its positions of annotation in the document and its IDF
            Map<String, Pair<List<OffsetPosition>,Double>> termProfiles = prepareTermProfiles(entities);
            
            // second pass, header
            if (resHeader != null) {
                // title
                List<LayoutToken> titleTokens = resHeader.getLayoutTokens(TaggingLabels.HEADER_TITLE);
                if (titleTokens != null) {
                    propagateLayoutTokenSequence(titleTokens, entities, termProfiles, termPattern, frequencies);
                } 

                // abstract
                List<LayoutToken> abstractTokens = resHeader.getLayoutTokens(TaggingLabels.HEADER_ABSTRACT);
                if (abstractTokens != null) {
                    propagateLayoutTokenSequence(abstractTokens, entities, termProfiles, termPattern, frequencies);
                } 

                // keywords
                List<LayoutToken> keywordTokens = resHeader.getLayoutTokens(TaggingLabels.HEADER_KEYWORD);
                if (keywordTokens != null) {
                    propagateLayoutTokenSequence(keywordTokens, entities, termProfiles, termPattern, frequencies);
                }
            }

            // second pass, body
            if (bodyClusters != null) {
                for (TaggingTokenCluster cluster : bodyClusters) {
                    if (cluster == null) {
                        continue;
                    }

                    TaggingLabel clusterLabel = cluster.getTaggingLabel();

                    List<LayoutToken> localTokenization = cluster.concatTokens();
                    if ((localTokenization == null) || (localTokenization.size() == 0))
                        continue;

                    if (clusterLabel.equals(TaggingLabels.PARAGRAPH) || clusterLabel.equals(TaggingLabels.ITEM)) {
                        //|| clusterLabel.equals(TaggingLabels.SECTION) ) {
                        propagateLayoutTokenSequence(localTokenization, entities, termProfiles, termPattern, frequencies);
                    } else if (clusterLabel.equals(TaggingLabels.TABLE)) {
                        //propagateLayoutTokenSequence(localTokenization, entities, termProfiles, termPattern, frequencies);
                    } else if (clusterLabel.equals(TaggingLabels.FIGURE)) {
                        //propagateLayoutTokenSequence(localTokenization, entities, termProfiles, termPattern, frequencies);
                    }
                }
            }

            // second pass, annex
            /*documentParts = doc.getDocumentPart(SegmentationLabels.ANNEX);
            if (documentParts != null) {
                List<LayoutToken> tokenizationParts = doc.getTokenizationParts(documentParts, doc.getTokenizations());
                propagateLayoutTokenSequence(tokenizationParts, entities, termProfiles, termPattern, frequencies);
            }*/

            // second pass, footnotes (if relevant)
            /*documentParts = doc.getDocumentPart(SegmentationLabel.FOOTNOTE);
            if (documentParts != null) {
                List<LayoutToken> tokenizationParts = doc.getTokenizationParts(documentParts, doc.getTokenizations());
                propagateLayoutTokenSequence(tokenizationParts, entities, termProfiles, termPattern, frequencies);
            }*/            

            // finally we attach and match bibliographical reference callout
            //List<LayoutToken> tokenizations = layoutTokenization.getTokenization();
            TEIFormatter formatter = new TEIFormatter(doc, parsers.getFullTextParser());
            // second pass, body
            if ( (bodyClusters != null) && (resCitations != null) && (resCitations.size() > 0) ) {
                List<BiblioComponent> bibRefComponents = new ArrayList<BiblioComponent>();
                for (TaggingTokenCluster cluster : bodyClusters) {
                    if (cluster == null) {
                        continue;
                    }

                    TaggingLabel clusterLabel = cluster.getTaggingLabel();

                    List<LayoutToken> localTokenization = cluster.concatTokens();
                    if ((localTokenization == null) || (localTokenization.size() == 0))
                        continue;

                    if (clusterLabel.equals(TaggingLabels.CITATION_MARKER)) {
                        List<LayoutToken> refTokens = TextUtilities.dehyphenize(localTokenization);
                        String chunkRefString = LayoutTokensUtil.toText(refTokens);

                        List<nu.xom.Node> refNodes = formatter.markReferencesTEILuceneBased(refTokens,
                                    doc.getReferenceMarkerMatcher(),
                                    true, // generate coordinates
                                    false); // do not mark unsolved callout as ref
                        /*else if (clusterLabel.equals(TaggingLabels.FIGURE_MARKER)) {
                            refNodes = markReferencesFigureTEI(chunkRefString, refTokens, figures,
                                    config.isGenerateTeiCoordinates("ref"));
                        } else if (clusterLabel.equals(TaggingLabels.TABLE_MARKER)) {
                            refNodes = markReferencesTableTEI(chunkRefString, refTokens, tables,
                                    config.isGenerateTeiCoordinates("ref"));
                        } else if (clusterLabel.equals(TaggingLabels.EQUATION_MARKER)) {
                            refNodes = markReferencesEquationTEI(chunkRefString, refTokens, equations,
                                    config.isGenerateTeiCoordinates("ref"));                    
                        } else {
                            throw new IllegalStateException("Unsupported marker type: " + clusterLabel);
                        }*/
                        if (refNodes != null) {                            
                            for (nu.xom.Node refNode : refNodes) {
                                if (refNode instanceof Element) {
                                    // get the bib ref key
                                    String refKey = ((Element)refNode).getAttributeValue("target");
//System.out.println(refKey + " / " + refNode.getValue());                             
                                    if (refKey == null)
                                        continue;

                                    int refKeyVal = -1;
                                    if (refKey.startsWith("#b")) {
                                        refKey = refKey.substring(2, refKey.length());
                                        try {
                                            refKeyVal = Integer.parseInt(refKey);
                                        } catch(Exception e) {
                                            logger.warn("Invalid ref identifier: " + refKey);
                                        }
                                    }
                                    if (refKeyVal == -1)
                                        continue;

                                    // get the bibref object
                                    BibDataSet resBib = resCitations.get(refKeyVal);
                                    if (resBib != null) {
                                        BiblioComponent biblioComponent = new BiblioComponent(resBib.getResBib(), refKeyVal);
                                        biblioComponent.setRawForm(refNode.getValue());
                                        biblioComponent.setOffsetStart(refTokens.get(0).getOffset());
                                        biblioComponent.setOffsetEnd(refTokens.get(refTokens.size()-1).getOffset() + 
                                            refTokens.get(refTokens.size()-1).getText().length());
                                        List<BoundingBox> boundingBoxes = BoundingBoxCalculator.calculate(refTokens);
                                        biblioComponent.setBoundingBoxes(boundingBoxes);
                                        bibRefComponents.add(biblioComponent);
                                        //break;
                                    }
                                }
                            }
                            
                        }
                    }
                }

                if (bibRefComponents.size() > 0) {
                    // avoid having version number where we identified bibliographical reference
                    entities = filterByRefCallout(entities, bibRefComponents);
                    // attach references to software entities 
                    entities = attachRefBib(entities, bibRefComponents);
                }

                // propagate the bib. ref. to the entities corresponding to the same software name without bib. ref.
                for(SoftwareEntity entity1 : entities) {
                    if (entity1.getBibRefs() != null && entity1.getBibRefs().size() > 0) {
                        for (SoftwareEntity entity2 : entities) {
                            if (entity2.getBibRefs() != null) {
                                continue;
                            }
                            if (entity2.getSoftwareName() != null && 
                                entity2.getSoftwareName().getRawForm().equals(entity1.getSoftwareName().getRawForm())) {
                                    entity2.setBibRefs(entity1.getBibRefs());
                            }
                        }
                    }
                }
            }

            System.out.println(entities.size() + " total software entities");  
            // propagate the non-disambiguated entities attributes to the new propagated entities corresponding 
            // to the same software name
            for(SoftwareEntity entity1 : entities) {
                if (entity1.getSoftwareName() != null) {
                    for (SoftwareEntity entity2 : entities) {
                        if (entity2.getSoftwareName() != null && 
                            entity2.getSoftwareName().getNormalizedForm().equals(entity1.getSoftwareName().getNormalizedForm())) {
                            SoftwareEntity.merge(entity1, entity2);
                            if (entity1.getSoftwareName().getWikidataId() != null && entity2.getSoftwareName().getWikidataId() == null) {
                                entity1.getSoftwareName().copyKnowledgeInformationTo(entity2.getSoftwareName());
                                entity2.getSoftwareName().setLang(entity1.getSoftwareName().getLang());
                            } else if (entity2.getSoftwareName().getWikidataId() != null && entity1.getSoftwareName().getWikidataId() == null) {
                                entity2.getSoftwareName().copyKnowledgeInformationTo(entity1.getSoftwareName());
                                entity1.getSoftwareName().setLang(entity2.getSoftwareName().getLang());
                            }
                        }
                    }
                }
            }

            Collections.sort(entities);

        } catch (Exception e) {
            e.printStackTrace();
            throw new GrobidException("Cannot process pdf file: " + file.getPath());
        }

        Collections.sort(entities);
        //return new Pair<List<SoftwareEntity>,Document>(entities, doc);
        return Pair.of(entities, doc);
    }

    /**
     * Process with the software model a segment coming from the segmentation model
     */
    private List<SoftwareEntity> processDocumentPart(SortedSet<DocumentPiece> documentParts, 
                                                  Document doc,
                                                  List<SoftwareEntity> entities,
                                                  boolean disambiguate) {
        List<LayoutToken> tokenizationParts = doc.getTokenizationParts(documentParts, doc.getTokenizations());
        return processLayoutTokenSequence(tokenizationParts, entities, disambiguate);
    }

    /**
     * Process with the software model an arbitrary sequence of LayoutToken objects
     */ 
    private List<SoftwareEntity> processLayoutTokenSequence(List<LayoutToken> layoutTokens, 
                                                            List<SoftwareEntity> entities,
                                                            boolean disambiguate) {
        List<LayoutTokenization> layoutTokenizations = new ArrayList<LayoutTokenization>();
        layoutTokenizations.add(new LayoutTokenization(layoutTokens));
        return processLayoutTokenSequences(layoutTokenizations, entities, disambiguate);
    }

    /**
     * Process with the software model a set of arbitrary sequence of LayoutTokenization
     */ 
    private List<SoftwareEntity> processLayoutTokenSequences(List<LayoutTokenization> layoutTokenizations, 
                                                  List<SoftwareEntity> entities, 
                                                  boolean disambiguate) {
        for(LayoutTokenization layoutTokenization : layoutTokenizations) {
            List<LayoutToken> layoutTokens = layoutTokenization.getTokenization();
            layoutTokens = SoftwareAnalyzer.getInstance().retokenizeLayoutTokens(layoutTokens);

            if ( (layoutTokens == null) || (layoutTokens.size() == 0) )
                continue;

            // text of the selected segment
            String text = LayoutTokensUtil.toText(layoutTokens);
            
            // positions for lexical match
            List<OffsetPosition> softwareTokenPositions = softwareLexicon.tokenPositionsSoftwareNames(layoutTokens);
            
            // string representation of the feature matrix for CRF lib
            String ress = addFeatures(layoutTokens, softwareTokenPositions);     
           
            // labeled result from CRF lib
            String res = label(ress);

            List<SoftwareComponent> components = extractSoftwareComponents(text, res, layoutTokens);

            List<SoftwareEntity> localEntities = groupByEntities(components);

            // disambiguation
            if (disambiguate)
                localEntities = disambiguator.disambiguate(localEntities, layoutTokens);

            entities.addAll(localEntities);
        }
        return entities;
    }

    /**
     * Process with the software model a set of arbitrary sequence of LayoutTokenization
     * from tables and figures, where the content is not structured (yet)
     */ 
    private List<SoftwareEntity> processLayoutTokenSequenceTableFigure(List<LayoutToken> layoutTokens, 
                                                  List<SoftwareEntity> entities, 
                                                  boolean disambiguate) {

        layoutTokens = SoftwareAnalyzer.getInstance().retokenizeLayoutTokens(layoutTokens);

        int pos = 0;
        List<LayoutToken> localLayoutTokens = null;
        while(pos < layoutTokens.size()) { 
            while((pos < layoutTokens.size()) && !layoutTokens.get(pos).getText().equals("\n")) {
                if (localLayoutTokens == null)
                    localLayoutTokens = new ArrayList<LayoutToken>();
                localLayoutTokens.add(layoutTokens.get(pos));
                pos++;
            }

            if ( (localLayoutTokens == null) || (localLayoutTokens.size() == 0) ) {
                pos++;
                continue;
            }

            // text of the selected segment
            String text = LayoutTokensUtil.toText(localLayoutTokens);

            // positions for lexical match
            List<OffsetPosition> softwareTokenPositions = softwareLexicon.tokenPositionsSoftwareNames(localLayoutTokens);
            
            // string representation of the feature matrix for CRF lib
            String ress = addFeatures(localLayoutTokens, softwareTokenPositions);     
            
            // labeled result from CRF lib
            String res = label(ress);
    //System.out.println(res);
            List<SoftwareComponent> components = extractSoftwareComponents(text, res, localLayoutTokens);

            // we group the identified components by full entities
            List<SoftwareEntity> localEntities = groupByEntities(components);

            // disambiguation
            if (disambiguate)
                localEntities = disambiguator.disambiguate(localEntities, localLayoutTokens);

            entities.addAll(localEntities);

            localLayoutTokens = null;
            pos++;
        }
       
        return entities;
    }

    private List<SoftwareEntity> propagateLayoutTokenSequence(List<LayoutToken> layoutTokens, 
                                              List<SoftwareEntity> entities,
                                              Map<String, Pair<List<OffsetPosition>,Double>> termProfiles,
                                              FastMatcher termPattern, 
                                              Map<String, Integer> frequencies) {
        List<OffsetPosition> results = termPattern.matchLayoutToken(layoutTokens, true, true);
        // ignore delimiters, but case sensitive matching
        if ( (results == null) || (results.size() == 0) ) {
            return entities;
        }
        
        for(OffsetPosition position : results) {
            List<LayoutToken> matchedTokens = layoutTokens.subList(position.start, position.end+1);
            OffsetPosition localPosition = new OffsetPosition(matchedTokens.get(0).getOffset(), 
                matchedTokens.get(matchedTokens.size()-1).getOffset() + matchedTokens.get(matchedTokens.size()-1).getText().length());

            String term = LayoutTokensUtil.toText(matchedTokens);
            int termFrequency = 1;
            if (frequencies != null && frequencies.get(term) != null)
                termFrequency = frequencies.get(term);

            // check the tf-idf of the term
            double tfidf = -1.0;
            if (termProfiles.get(term) != null) {
                // is the match already present in the entity list? 
                List<OffsetPosition> thePositions = termProfiles.get(term).getLeft();
                if (containsPosition(thePositions, localPosition)) {
                    continue;
                }
                tfidf = termFrequency * termProfiles.get(term).getRight();
            }
            // ideally we should make a small classifier here with entity frequency, tfidf, disambiguation success and 
            // and/or log-likelyhood/dice coefficient as features - but for the time being we introduce a simple rule
            // with an experimentally defined threshold:
            if ( (tfidf <= 0) || (tfidf > 0.001) ) {
                // add new entity mention
                SoftwareComponent name = new SoftwareComponent();
                name.setRawForm(term);
                name.setOffsetStart(localPosition.start);
                name.setOffsetEnd(localPosition.end);
                name.setLabel(SoftwareTaggingLabels.SOFTWARE);
                name.setTokens(matchedTokens);

                List<BoundingBox> boundingBoxes = BoundingBoxCalculator.calculate(matchedTokens);
                name.setBoundingBoxes(boundingBoxes);

                SoftwareEntity entity = new SoftwareEntity();
                entity.setSoftwareName(name);
                entity.setType(SoftwareLexicon.Software_Type.SOFTWARE);
                // add disambiguation infos if any
                for(SoftwareEntity ent : entities) {
                    if (ent.getSoftwareName().getWikipediaExternalRef() != -1) {
                        if (term.equals(ent.getSoftwareName().getRawForm())) {
                            ent.getSoftwareName().copyKnowledgeInformationTo(name);
                            name.setLang(ent.getSoftwareName().getLang());
                            // add reference if present
                            entity.setBibRefs(ent.getBibRefs());
                            // Note: TBD - disamb entity and bib ref should be generalized more widely to all entities
                            // sharing the software name
                            break;
                        }
                    }
                }

                entities.add(entity);
            }
        }

        return entities;
    }

    private boolean containsPosition(final List<OffsetPosition> list, final OffsetPosition position) {
        for (OffsetPosition pos : list) {
            //if (pos.start == position.start && pos.end == position.end)  
            if (pos.start == position.start)  
                return true;
        } 
        return false;
    }

    /**
     * Identify components corresponding to the same software entities
     */
    public List<SoftwareEntity> groupByEntities(List<SoftwareComponent> components) {
//System.out.println(components.size() + " components found");

        // we anchor the process to the software names and aggregate other closest components
        // to form full entities
        List<SoftwareEntity> entities = new ArrayList<SoftwareEntity>();
        SoftwareEntity currentEntity = null;
        // first pass for creating entities based on software names
        for(SoftwareComponent component : components) {
//System.out.println(component.toJson());
//System.out.println(component.getLabel().getLabel());
            if (component.getLabel().equals(SoftwareTaggingLabels.SOFTWARE)) {
//System.out.println("entity added");                
                currentEntity = new SoftwareEntity();
                currentEntity.setSoftwareName(component);
                currentEntity.setType(SoftwareLexicon.Software_Type.SOFTWARE);
                entities.add(currentEntity);
            }
        }

        // second pass for aggregating other components
        int n = 0; // index in entities
        SoftwareEntity previousEntity = null;
        currentEntity = null;
        if (entities.size() == 0)
            return entities;
        if (entities.size() > 1) {
            previousEntity = entities.get(0);
            currentEntity = entities.get(1);
            n = 1;
        } else {
            previousEntity = entities.get(0);
        }

        for(SoftwareComponent component : components) {
            if (component.getLabel().equals(SoftwareTaggingLabels.SOFTWARE))
                continue;

//System.out.println(component.toJson());
//System.out.println(component.getLabel().getLabel());

            while ( (currentEntity != null) && 
                 (component.getOffsetStart() >= currentEntity.getSoftwareName().getOffsetEnd()) ) {
                previousEntity = currentEntity;
                if (n < entities.size())
                    currentEntity = entities.get(n);
                n += 1;
                if (n >= entities.size())
                    break;
            }
            if (currentEntity == null) {
                if (previousEntity.freeField(component.getLabel())) {
                    previousEntity.setComponent(component);
                }
            } else if (component.getOffsetEnd() < previousEntity.getSoftwareName().getOffsetStart()) {
                if (previousEntity.freeField(component.getLabel())) {
                    previousEntity.setComponent(component);
                }
            } else if (component.getOffsetEnd() < currentEntity.getSoftwareName().getOffsetStart()) {
                // we are in the middle of the two entities, we use proximity to attach the component
                // to an entity, with a strong bonus to the entity on the left 
                // using sentence boundary could be helpful too in this situation
                int dist1 = currentEntity.getSoftwareName().getOffsetStart() - component.getOffsetEnd();
                int dist2 = component.getOffsetStart() - previousEntity.getSoftwareName().getOffsetEnd(); 
                if (dist2 <= dist1*2) {
                    previousEntity.setComponent(component);
                } else
                    currentEntity.setComponent(component);
            } else if (component.getOffsetEnd() >= currentEntity.getSoftwareName().getOffsetEnd()) {
                currentEntity.setComponent(component);
            }
        }
        return entities;
    }


    /**
     * Try to attach relevant bib ref component to software entities
     */
    public List<SoftwareEntity> attachRefBib(List<SoftwareEntity> entities, List<BiblioComponent> refBibComponents) {

        // we anchor the process to the software names and aggregate other closest components on the right
        // if we cross a bib ref component we attach it, if a bib ref component is just after the last 
        // component of the entity group, we attach it 
        for(SoftwareEntity entity : entities) {
            // find the name component
            SoftwareComponent nameComponent = entity.getSoftwareName();
            int pos = nameComponent.getOffsetEnd();
            
            // find end boundary
            int endPos = pos;
            List<SoftwareComponent> theComps = new ArrayList<SoftwareComponent>();
            SoftwareComponent comp = entity.getVersion();
            if (comp != null) 
                theComps.add(comp);
            /*comp = entity.getVersionDate();
            if (comp != null) 
                theComps.add(comp);*/
            comp = entity.getCreator();
            if (comp != null) 
                theComps.add(comp);
            comp = entity.getSoftwareURL();
            if (comp != null) 
                theComps.add(comp);

            for(SoftwareComponent theComp : theComps) {
                int localPos = theComp.getOffsetEnd();
                if (localPos > endPos)
                    endPos = localPos;
            }

            // find included or just next bib ref callout
            for(BiblioComponent refBib : refBibComponents) {
//System.out.println("bib ref component at " + refBib.getOffsetStart() + " / " + refBib.getRefKey());
                if ( (refBib.getOffsetStart() >= pos) &&
                     (refBib.getOffsetStart() <= endPos+5) ) {
//System.out.println("bib ref attached / " +  refBib.getRefKey()); 
                    entity.addBibRef(refBib);
                    endPos = refBib.getOffsetEnd();
                }
            }
        }
        
        return entities;
    }


    /**
     * Avoid having a version number where we identified a rederence callout
     */
    public List<SoftwareEntity> filterByRefCallout(List<SoftwareEntity> entities, List<BiblioComponent> refBibComponents) {
        for(BiblioComponent refBib : refBibComponents) {
            for(SoftwareEntity entity : entities) {
                if (entity.getVersion() == null)
                    continue;
                SoftwareComponent version = entity.getVersion();
                if ( (refBib.getOffsetStart() >= version.getOffsetStart()) &&
                     (refBib.getOffsetEnd() <= version.getOffsetEnd()) ) {
                    entity.setVersion(null);
                }
            }
        }
        return entities;
    }


	/**
	 *
	 */
    public int batchProcess(String inputDirectory,
                            String outputDirectory,
                            boolean isRecursive) throws IOException {
		// TBD
        return 0;
    }

    /**
     * Give the list of textual tokens from a list of LayoutToken
     */
    /*private static List<String> getTexts(List<LayoutToken> tokenizations) {
        List<String> texts = new ArrayList<>();
        for (LayoutToken token : tokenizations) {
            if (isNotEmpty(trim(token.getText())) && 
                !token.getText().equals(" ") &&
                !token.getText().equals("\n") && 
                !token.getText().equals("\r") &&  
                !token.getText().equals("\t") && 
                !token.getText().equals("\u00A0")) {
                    texts.add(token.getText());
            }
        }
        return texts;
    }*/

    /**
     * Process the content of the specified input file and format the result as training data.
     * <p>
     * Input file can be (i)) PDF (.pdf) and it is assumed that we have a scientific article which will
     * be processed by GROBID full text first, (ii) some text (.txt extension).
	 *
	 * Note that we could consider a third input type which would be a TEI file resuling from the
	 * conversion of a publisher's native XML file following Pub2TEI transformatiom/standardization.
     *
     * @param inputFile input file
     * @param pathTEI   path to TEI with annotated training data
     * @param id        id
     */
    public void createTraining(String inputFile,
                               String pathTEI,
                               int id) throws Exception {
        File file = new File(inputFile);
        if (!file.exists()) {
            throw new GrobidException("Cannot create training data because input file can not be accessed: " + inputFile);
        }

        Element root = getTEIHeader("_" + id);
        if (inputFile.endsWith(".txt") || inputFile.endsWith(".TXT")) {
            root = createTrainingText(file, root);
        } else if (inputFile.endsWith(".pdf") || inputFile.endsWith(".PDF")) {
            root = createTrainingPDF(file, root);
        }

        if (root != null) {
            //System.out.println(XmlBuilderUtils.toXml(root));
            try {
                FileUtils.writeStringToFile(new File(pathTEI), XmlBuilderUtils.toXml(root));
            } catch (IOException e) {
                throw new GrobidException("Cannot create training data because output file can not be accessed: " + pathTEI);
            }
        }
    }

	/**
	 * Generate training data with the current model using new files located in a given directory.
	 * the generated training data can then be corrected manually to be used for updating the
	 * software CRF model.
     */
    @SuppressWarnings({"UnusedParameters"})
    public int createTrainingBatch(String inputDirectory,
                                   String outputDirectory,
                                   int ind) throws IOException {
        try {
            File path = new File(inputDirectory);
            if (!path.exists()) {
                throw new GrobidException("Cannot create training data because input directory can not be accessed: " + inputDirectory);
            }

            File pathOut = new File(outputDirectory);
            if (!pathOut.exists()) {
                throw new GrobidException("Cannot create training data because ouput directory can not be accessed: " + outputDirectory);
            }

            // we process all pdf files in the directory
            File[] refFiles = path.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    System.out.println(name);
                    return name.endsWith(".pdf") || name.endsWith(".PDF") ||
                            name.endsWith(".txt") || name.endsWith(".TXT");// ||
//                            name.endsWith(".xml") || name.endsWith(".tei") ||
 //                           name.endsWith(".XML") || name.endsWith(".TEI");
                }
            });

            if (refFiles == null)
                return 0;

            System.out.println(refFiles.length + " files to be processed.");

            int n = 0;
            if (ind == -1) {
                // for undefined identifier (value at -1), we initialize it to 0
                n = 1;
            }
            for (final File file : refFiles) {
                try {
                    String pathTEI = outputDirectory + "/" + file.getName().substring(0, file.getName().length() - 4) + ".training.tei.xml";
                    createTraining(file.getAbsolutePath(), pathTEI, n);
                } catch (final Exception exp) {
                    logger.error("An error occured while processing the following pdf: "
                            + file.getPath() + ": " + exp);
                }
                if (ind != -1)
                    n++;
            }

            return refFiles.length;
        } catch (final Exception exp) {
            throw new GrobidException("An exception occured while running Grobid batch.", exp);
        }
    }

	/**
	  * Generate training data from a text file
	  */
    private Element createTrainingText(File file, Element root) throws IOException {
        String text = FileUtils.readFileToString(file, "UTF-8");

        Element textNode = teiElement("text");
        // for the moment we suppose we have english only...
        textNode.addAttribute(new Attribute("xml:lang", "http://www.w3.org/XML/1998/namespace", "en"));

        // we process the text paragraph by paragraph
        String lines[] = text.split("\n");
        StringBuilder paragraph = new StringBuilder();
        List<SoftwareComponent> components = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() != 0) {
                paragraph.append(line).append("\n");
            }
            if (((line.length() == 0) || (i == lines.length - 1)) && (paragraph.length() > 0)) {
                // we have a new paragraph
                text = paragraph.toString().replace("\n", " ").replace("\r", " ").replace("\t", " ");
                List<LayoutToken> tokens = SoftwareAnalyzer.getInstance().tokenizeWithLayoutToken(text);

                if (tokens.size() == 0)
                    continue;

                // to store unit term positions
                List<OffsetPosition> softwareTokenPositions = softwareLexicon.tokenPositionsSoftwareNames(tokens);
                String ress = addFeatures(tokens, softwareTokenPositions);
                String res = null;
                try {
                    res = label(ress);
                } catch (Exception e) {
                    throw new GrobidException("CRF labeling for software mention parsing failed.", e);
                }
                components = extractSoftwareComponents(text, res, tokens);

                textNode.appendChild(trainingExtraction(components, text, tokens));
                paragraph = new StringBuilder();
            }
        }
        root.appendChild(textNode);

        return root;
    }

	/**
	  * Generate training data from a PDf file
	  */
    private Element createTrainingPDF(File file, Element root) throws IOException {
        // first we apply GROBID fulltext model on the PDF to get the full text TEI
        Document teiDoc = null;
        try {
            teiDoc = GrobidFactory.getInstance().createEngine().fullTextToTEIDoc(file, GrobidAnalysisConfig.defaultInstance());
        } catch (Exception e) {
            e.printStackTrace();
            throw new GrobidException("Cannot create training data because GROBID full text model failed on the PDF: " + file.getPath());
        }
        if (teiDoc == null) {
            return null;
        }

        String teiXML = teiDoc.getTei();
		FileUtils.writeStringToFile(new File(file.getPath()+".tei.xml"), teiXML);

        // we parse this TEI string similarly as for createTrainingXML

        List<SoftwareComponent> components = null;

        Element textNode = teiElement("text");
        // for the moment we suppose we have english only...
        textNode.addAttribute(new Attribute("xml:lang", "http://www.w3.org/XML/1998/namespace", "en"));

        try {
            // get a factory for SAX parser
            SAXParserFactory spf = SAXParserFactory.newInstance();

            TextChunkSaxHandler handler = new TextChunkSaxHandler();

            //get a new instance of parser
            SAXParser p = spf.newSAXParser();
            p.parse(new InputSource(new StringReader(teiXML)), handler);

            List<String> chunks = handler.getChunks();
            for (String text : chunks) {
                text = text.toString().replace("\n", " ").replace("\r", " ").replace("\t", " ");
                // the last one is a special "large" space missed by the regex "\\p{Space}+" used on the SAX parser
                if (text.trim().length() == 0)
                    continue;
                List<LayoutToken> tokenizations = SoftwareAnalyzer.getInstance().tokenizeWithLayoutToken(text);

                if (tokenizations.size() == 0)
                    continue;

                // to store unit term positions
                List<OffsetPosition> softwareTokenPositions = softwareLexicon.tokenPositionsSoftwareNames(tokenizations);
                String ress = addFeatures(tokenizations, softwareTokenPositions);
                String res = null;
                try {
                    res = label(ress);
                } catch (Exception e) {
                    throw new GrobidException("CRF labeling for software parsing failed.", e);
                }
                components = extractSoftwareComponents(text, res, tokenizations);

                textNode.appendChild(trainingExtraction(components, text, tokenizations));
            }
            root.appendChild(textNode);
        } catch (Exception e) {
            e.printStackTrace();
            throw new GrobidException("Cannot create training data because input PDF/XML file can not be parsed: " + 
                file.getPath());
        }

        return root;
    }

    private Map<String, Pair<List<OffsetPosition>,Double>> prepareTermProfiles(List<SoftwareEntity> entities) {
        Map<String, Pair<List<OffsetPosition>,Double>> result = new TreeMap<String, Pair<List<OffsetPosition>,Double>>();

        for(SoftwareEntity entity : entities) {
            SoftwareComponent nameComponent = entity.getSoftwareName();
            if (nameComponent == null)
                continue;
            String term = nameComponent.getRawForm();
            Pair<List<OffsetPosition>,Double> profile = result.get(term);
            if (profile == null) {
                List<OffsetPosition> localPositions = new ArrayList<OffsetPosition>();
                List<LayoutToken> localTokens = nameComponent.getTokens();
                localPositions.add(new OffsetPosition(localTokens.get(0).getOffset(), 
                    localTokens.get(localTokens.size()-1).getOffset() + localTokens.get(localTokens.size()-1).getText().length()-1));
                profile = Pair.of(localPositions, SoftwareLexicon.getInstance().getTermIDF(term));
            } else {
                List<OffsetPosition> localPositions = profile.getLeft();
                if (localPositions == null)
                    localPositions = new ArrayList<OffsetPosition>();
                List<LayoutToken> localTokens = nameComponent.getTokens();
                localPositions.add(new OffsetPosition(localTokens.get(0).getOffset(), 
                    localTokens.get(localTokens.size()-1).getOffset() + localTokens.get(localTokens.size()-1).getText().length()-1));
                profile = Pair.of(localPositions, 
                                  SoftwareLexicon.getInstance().getTermIDF(term));
            }
            result.put(term, profile);
        }

        return result;
    } 

    private FastMatcher prepareTermPattern(List<SoftwareEntity> entities) {
        FastMatcher termPattern = new FastMatcher();
        for(SoftwareEntity entity : entities) {
            SoftwareComponent nameComponent = entity.getSoftwareName();
            if (nameComponent == null)
                continue;
            String term = nameComponent.getRawForm();

            termPattern.loadTerm(term, SoftwareAnalyzer.getInstance());
        }
        return termPattern;
    }

    private Map<String, Integer> prepareFrequencies(List<SoftwareEntity> entities, List<LayoutToken> tokens) {
        Map<String, Integer> frequencies = new TreeMap<String, Integer>();
        for(SoftwareEntity entity : entities) {
            SoftwareComponent nameComponent = entity.getSoftwareName();
            if (nameComponent == null)
                continue;
            String term = nameComponent.getRawForm();
            if (frequencies.get(term) == null) {
                FastMatcher termPattern = new FastMatcher();
                termPattern.loadTerm(term, SoftwareAnalyzer.getInstance());
                List<OffsetPosition> results = termPattern.matchLayoutToken(tokens, true, true);
                // ignore delimiters, but case sensitive matching
                int freq = 0;
                if (results != null) {  
                    freq = results.size();
                }
                frequencies.put(term, new Integer(freq));
            }
        }
        return frequencies;
    }

    @SuppressWarnings({"UnusedParameters"})
    public String addFeatures(List<LayoutToken> tokens,
                               List<OffsetPosition> softwareTokenPositions) {
        int totalLine = tokens.size();
        int posit = 0;
        int currentSoftwareIndex = 0;
        List<OffsetPosition> localPositions = softwareTokenPositions;
        boolean isSoftwarePattern = false;
        StringBuilder result = new StringBuilder();
        try {
            for (LayoutToken token : tokens) {
                if (token.getText().trim().equals("@newline")) {
                    result.append("\n");
                    posit++;
                    continue;
                }

                String text = token.getText();
                if (text.equals(" ") || text.equals("\n")) {
                    posit++;
                    continue;
                }

                // parano normalisation
                text = UnicodeUtil.normaliseTextAndRemoveSpaces(text);
                if (text.trim().length() == 0 ) {
                    posit++;
                    continue;
                }

                // do we have a unit at position posit?
                if ((localPositions != null) && (localPositions.size() > 0)) {
                    for (int mm = currentSoftwareIndex; mm < localPositions.size(); mm++) {
                        if ((posit >= localPositions.get(mm).start) && (posit <= localPositions.get(mm).end)) {
                            isSoftwarePattern = true;
                            currentSoftwareIndex = mm;
                            break;
                        } else if (posit < localPositions.get(mm).start) {
                            isSoftwarePattern = false;
                            break;
                        } else if (posit > localPositions.get(mm).end) {
                            continue;
                        }
                    }
                }

                FeaturesVectorSoftware featuresVector =
                        FeaturesVectorSoftware.addFeaturesSoftware(text, null, isSoftwarePattern);
                result.append(featuresVector.printVector());
                result.append("\n");
                posit++;
                isSoftwarePattern = false;
            }
        } catch (Exception e) {
            throw new GrobidException("An exception occured while running Grobid.", e);
        }
        return result.toString();
    }

    /**
     * Extract identified software components from a CRF labelled text.
     */
    public List<SoftwareComponent> extractSoftwareComponents(String text,
                                                	String result,
                                                	List<LayoutToken> tokenizations) {
        List<SoftwareComponent> components = new ArrayList<>();

        TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.SOFTWARE, result, tokenizations);
        List<TaggingTokenCluster> clusters = clusteror.cluster();

        SoftwareComponent currentComponent = null;
        SoftwareLexicon.Software_Type openEntity = null;
//System.out.println(result);
        int pos = 0; // position in term of characters for creating the offsets

        for (TaggingTokenCluster cluster : clusters) {
            if (cluster == null) {
                continue;
            }

            TaggingLabel clusterLabel = cluster.getTaggingLabel();
            List<LayoutToken> theTokens = cluster.concatTokens();
            String clusterContent = LayoutTokensUtil.toText(cluster.concatTokens()).trim();
  
            if ((pos < text.length()-1) && (text.charAt(pos) == ' '))
                pos += 1;
            if ((pos < text.length()-1) && (text.charAt(pos) == '\n'))
                pos += 1;
            
            int endPos = pos;
            boolean start = true;
            for (LayoutToken token : theTokens) {
                if (token.getText() != null) {
                    if (start && token.getText().equals(" ")) {
                        pos++;
                        endPos++;
                        continue;
                    }
                    if (start)
                        start = false;
                    endPos += token.getText().length();
                }
            }

            if ((endPos > 0) && (text.length() >= endPos) && (text.charAt(endPos-1) == '\n'))
        		endPos--;
            if ((endPos > 0) && (text.length() >= endPos) && (text.charAt(endPos-1) == ' '))
                endPos--;

            if (!clusterLabel.equals(SoftwareTaggingLabels.OTHER)) {
                currentComponent = new SoftwareComponent();

                currentComponent.setRawForm(clusterContent);
                //currentComponent.setOffsetStart(pos);
                currentComponent.setOffsetStart(theTokens.get(0).getOffset());

                //currentComponent.setOffsetEnd(endPos);
                currentComponent.setOffsetEnd(theTokens.get(theTokens.size()-1).getOffset() + theTokens.get(theTokens.size()-1).getText().length());


                currentComponent.setLabel(clusterLabel);
                currentComponent.setTokens(theTokens);

				List<BoundingBox> boundingBoxes = BoundingBoxCalculator.calculate(cluster.concatTokens());
				currentComponent.setBoundingBoxes(boundingBoxes);

				components.add(currentComponent);
				currentComponent = null;
            } 
            
            pos = endPos;
        }

        return components;
    }

	/**
	 *  Add XML annotations corresponding to components in a piece of text, to be included in
	 *  generated training data.
	 */
    public Element trainingExtraction(List<SoftwareComponent> components, String text, List<LayoutToken> tokenizations) {
        Element p = teiElement("p");

        int pos = 0;
		if ( (components == null) || (components.size() == 0) )
			p.appendChild(text);
        for (SoftwareComponent component : components) {
            Element componentElement = teiElement("rs");

            //if (component.getLabel() != OTHER) 
            {
                componentElement.addAttribute(new Attribute("type", component.getLabel().getLabel()));

                int startE = component.getOffsetStart();
                int endE = component.getOffsetEnd();

				p.appendChild(text.substring(pos, startE));
                componentElement.appendChild(text.substring(startE, endE));
                pos = endE;
            }
            p.appendChild(componentElement);
        }
        p.appendChild(text.substring(pos, text.length()));

        return p;
    }

    /**
     *  Create a standard TEI header to be included in the TEI training files.
     */
    static public nu.xom.Element getTEIHeader(String id) {
        Element tei = teiElement("tei");
        Element teiHeader = teiElement("teiHeader");

        if (id != null) {
            Element fileDesc = teiElement("fileDesc");
            fileDesc.addAttribute(new Attribute("xml:id", "http://www.w3.org/XML/1998/namespace", id));
            teiHeader.appendChild(fileDesc);
        }

        Element encodingDesc = teiElement("encodingDesc");

        Element appInfo = teiElement("appInfo");

        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ");
        df.setTimeZone(tz);
        String dateISOString = df.format(new java.util.Date());

        Element application = teiElement("application");
        application.addAttribute(new Attribute("version", GrobidProperties.getVersion()));
        application.addAttribute(new Attribute("ident", "GROBID"));
        application.addAttribute(new Attribute("when", dateISOString));

        Element ref = teiElement("ref");
        ref.addAttribute(new Attribute("target", "https://github.com/kermitt2/grobid"));
        ref.appendChild("A machine learning software for extracting information from scholarly documents");

        application.appendChild(ref);
        appInfo.appendChild(application);
        encodingDesc.appendChild(appInfo);
        teiHeader.appendChild(encodingDesc);
        tei.appendChild(teiHeader);

        return tei;
    }

    /**
     *  Create a simplified TEI header to be included in a TEI corpus file.
     */
    static public Element getTEIHeaderSimple(String id, BiblioItem biblio) {
        return getTEIHeaderSimple(id, biblio, null);
    }

    static public Element getTEIHeaderSimple(String id, BiblioItem biblio, String catCuration) {
        Element tei = teiElement("TEI");
        Element teiHeader = teiElement("teiHeader");

        if (id != null) {
            Element fileDesc = teiElement("fileDesc");
            fileDesc.addAttribute(new Attribute("xml:id", "http://www.w3.org/XML/1998/namespace", id));

            Element titleStatement = teiElement("titleStmt");
            Element title = teiElement("title");
            title.appendChild(biblio.getTitle());
            titleStatement.appendChild(title);
            fileDesc.appendChild(titleStatement);
            Element sourceDesc = teiElement("sourceDesc");
            Element bibl = teiElement("bibl");
            if (biblio.getDOI() != null) {
                Element idno = teiElement("idno");
                idno.addAttribute(new Attribute("DOI", null, biblio.getDOI()));
                bibl.appendChild(idno);
            }
            if (biblio.getPMCID() != null) {  
                Element idno = teiElement("idno");
                idno.addAttribute(new Attribute("PMC", null, biblio.getPMCID()));
                bibl.appendChild(idno);
            } else if (id.startsWith("PMC")) {
                Element idno = teiElement("idno");
                idno.addAttribute(new Attribute("PMC", null, id));
                bibl.appendChild(idno);
            }
            if (biblio.getPMID() != null) {
                Element idno = teiElement("idno");
                idno.addAttribute(new Attribute("PMID", null, biblio.getPMID()));
                bibl.appendChild(idno);
            }
            sourceDesc.appendChild(bibl);
            fileDesc.appendChild(sourceDesc);
            teiHeader.appendChild(fileDesc);
        }

        Element encodingDesc = teiElement("encodingDesc");
        teiHeader.appendChild(encodingDesc);

        if (catCuration != null) {
            Element profileDesc = teiElement("profileDesc");

            Element textClass = teiElement("textClass");
            Element catRef = teiElement("catRef");

            catRef.addAttribute(new Attribute("target", null, "#"+catCuration));

            textClass.appendChild(catRef);
            profileDesc.appendChild(textClass);

            teiHeader.appendChild(profileDesc);
        }

        tei.appendChild(teiHeader);

        return tei;
    }

	/**
	 *  Create training data from PDF with annotation layers corresponding to the entities.
	 */
	public int boostrapTrainingPDF(String inputDirectory,
                                   String outputDirectory,
                                   int ind) {
		return 0;
	}
}
