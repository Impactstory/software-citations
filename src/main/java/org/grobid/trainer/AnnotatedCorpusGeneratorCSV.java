package org.grobid.trainer;

import org.grobid.core.analyzers.SoftwareAnalyzer;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.utilities.ArticleUtilities;

import org.grobid.core.analyzers.GrobidAnalyzer;
import org.grobid.core.data.BibDataSet;
import org.grobid.core.data.BiblioItem;
import org.grobid.core.document.Document;
import org.grobid.core.document.DocumentPiece;
import org.grobid.core.document.DocumentSource;
import org.grobid.core.document.xml.XmlBuilderUtils;
import org.grobid.core.engines.Engine;
import org.grobid.core.engines.SoftwareParser;
import org.grobid.core.engines.FullTextParser;
import org.grobid.core.engines.config.GrobidAnalysisConfig;
import org.grobid.core.engines.label.SegmentationLabels;
import org.grobid.core.engines.label.TaggingLabel;
import org.grobid.core.engines.label.TaggingLabels;
import org.grobid.core.factory.GrobidFactory;
import org.grobid.core.lang.Language;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.layout.LayoutTokenization;
import org.grobid.core.lexicon.FastMatcher;
import org.grobid.core.main.LibraryLoader;
import org.grobid.core.utilities.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.NumberFormat;

import org.apache.commons.io.*;
import org.apache.commons.csv.*;

import org.semanticweb.yars.turtle.*;
import org.semanticweb.yars.nx.*;

import java.net.URI;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nu.xom.*;
import static org.grobid.core.document.xml.XmlBuilderUtils.teiElement;
import org.apache.commons.lang3.StringUtils;

/**
 * This class aims at converting annotations in .csv format from the original 
 * softcite dataset into annotated XML files (at document level) usable for training 
 * text mining tools and readable by humans. We convert into MUC conferences 
 * ENAMEX-style annotations (simpler than TEI for named-entities). 
 *
 * We need in particular to re-align the content of the original document which
 * has been annotated (e.g. a PMC article) with the "quotes" and strings available
 * in the .csv stuff. This is not always straightforward because: 
 * 
 * - the strings in the csv files has been cut and paste directly from the PDF 
 *   document, which is more noisy than what we can get from GROBID PDF parsing 
 *   pipeline,
 * - some annotations (like bibliographical reference, creators), refers to 
 *   unlocated information present in the document and we need some global document
 *   analysis to try to related the annotations with the right document 
 *   content.
 *
 * Just as a reference, I mention here that, from the text mining point of view,
 * a standard XML annotations framework like (MUC's ENAMEX or TEI style annotations) 
 * should be preferably used for reliable, constrained, readable and complete corpus 
 * annotations rather than the heavy and painful semantic web framework which 
 * is too disconnected from the actual linguistic and layout material. 
 *
 * Once the corpus is an XML format, we can use the consistency scripts under 
 * scripts/ to analyse, review and correct the annotations in a simple manner.
 *
 * Example command line:
 * mvn exec:java -Dexec.mainClass=org.grobid.trainer.AnnotatedCorpusGeneratorCSV 
 * -Dexec.args="/home/lopez/tools/softcite-dataset/pdf/ /home/lopez/tools/softcite-dataset/data/csv_dataset/ resources/dataset/software/corpus/"
 *
 *
 * @author Patrice
 */
public class AnnotatedCorpusGeneratorCSV {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedCorpusGeneratorCSV.class);

    static Charset UTF_8 = Charset.forName("UTF-8"); // StandardCharsets.UTF_8

    // TBD: use counter classes and a map
    private int totalMentions = 0;
    
    private int unmatchedSoftwareMentions = 0;
    private int unmatchedVersionNumberMentions = 0;
    private int unmatchedVersionDateMentions = 0;
    private int unmatchedCreatorMentions = 0;
    private int unmatchedUrlMentions = 0;

    private int totalSoftwareMentions = 0;
    private int totalVersionNumberMentions = 0;
    private int totalVersionDateMentions = 0;
    private int totalCreatorMentions = 0;
    private int totalUrlMentions = 0;

    private int totalContexts = 0;
    private int unmatchedContexts = 0;

    public static List<String> fields = Arrays.asList("software", "version-number", "version-date", "creator", "url");

    // these PDF fail with current version of GROBID, they will work with then next which will integrate pdfalto
    private List<String> failingPDF = Arrays.asList("PMC4153526", "PMC5378987"); 
    // hanging for first, pdf2xml error for second
    
    private ArticleUtilities articleUtilities = new ArticleUtilities();

    /**
     * Start the conversion/fusion process for generating MUC-style annotated XML documents
     * from PDF, parsed by GROBID core, and softcite dataset  
     */
    public void process(String documentPath, String csvPath, String xmlPath) throws IOException {
        
        Map<String, AnnotatedDocument> documents = new HashMap<String, AnnotatedDocument>();
        Map<String, SoftciteAnnotation> annotations = new HashMap<String, SoftciteAnnotation>();

        importCSVFiles(csvPath, documents, annotations);

        System.out.println("\n" + annotations.size() + " total annotations");
        System.out.println(documents.size() + " total annotated documents");    

        // breakdown per articleSet (e.g. pmc, econ)
        Map<String, Integer> articleSetMap = new TreeMap<String, Integer>();
        for (Map.Entry<String, AnnotatedDocument> entry : documents.entrySet()) {
            AnnotatedDocument document = entry.getValue();
            if (document.getArticleSet() != null) {
                int nb = 0;
                if (articleSetMap.get(document.getArticleSet()) != null)
                    nb = articleSetMap.get(document.getArticleSet())+1;
                else 
                    nb = 1;
                articleSetMap.put(document.getArticleSet(), nb);
            }
        }
        // go thought all annotated documents of softcite
        for (Map.Entry<String, Integer> entry : articleSetMap.entrySet()) {
            String setName = entry.getKey();
            int setCount = entry.getValue();
            System.out.println(setName + ": " + setCount + " documents");
        }

        // computing and reporting cross-agreement for the loaded set
        CrossAgreement crossAgreement = new CrossAgreement(fields);
        CrossAgreement.AgreementStatistics stats = crossAgreement.evaluate(documents, "econ_article"); 
        System.out.println("\n****** Inter-Annotator Agreement (Percentage agreement) ******** \n\n" + stats.toString());

        // we keep GROBID analysis as close as possible to the actual content
        GrobidAnalysisConfig config = new GrobidAnalysisConfig.GrobidAnalysisConfigBuilder()
                                    .consolidateHeader(0)
                                    .consolidateCitations(0)
                                    .build();
        //LibraryLoader.load();
        Engine engine = GrobidFactory.getInstance().getEngine();

        // for reporting unmatched mention/context 
        Writer writerSoftware = new PrintWriter(new BufferedWriter(new FileWriter("unmatched-software-mention-context.txt")));
        Writer writerVersionNumber = new PrintWriter(new BufferedWriter(new FileWriter("unmatched-version-number-mention-context.txt")));
        Writer writerVersionDate = new PrintWriter(new BufferedWriter(new FileWriter("unmatched-version-date-mention-context.txt")));
        Writer writerCreator = new PrintWriter(new BufferedWriter(new FileWriter("unmatched-creator-mention-context.txt")));
        Writer writerUrl = new PrintWriter(new BufferedWriter(new FileWriter("unmatched-url-mention-context.txt")));

        // go thought all annotated documents of softcite
        for (Map.Entry<String, AnnotatedDocument> entry : documents.entrySet()) {
            String docName = entry.getKey();
            File pdfFile = getPDF(documentPath, docName);

            if (failingPDF.contains(docName)) {
                // hanging currently, but it works with pdfalto
                continue;
            }

            DocumentSource documentSource = null;
            try {
                // process PDF documents with GROBID
                documentSource = DocumentSource.fromPdf(pdfFile, -1, -1, false, true, false);
            } catch(Exception e) {
                e.printStackTrace();
            }                

            if (documentSource == null)
                continue;

            Document doc = engine.getParsers().getSegmentationParser().processing(documentSource, config);

            if (doc == null) {
                logger.error("The parsing of the PDF file corresponding to " + docName + " failed");
                // TBD
                continue;
            }

            AnnotatedDocument document = entry.getValue();
            List<SoftciteAnnotation> localAnnotations = document.getAnnotations();
            //System.out.println(docName + ": " + localAnnotations.size() + " annotations");

            Language lang = new Language("en", 1.0);
            
            List<Integer> toExclude = new ArrayList<Integer>();

            // add more structures via GROBID
            // header
            SortedSet<DocumentPiece> documentParts = doc.getDocumentPart(SegmentationLabels.HEADER);
            List<LayoutToken> titleTokens = null;
            if (documentParts != null) {
                String header = engine.getParsers().getHeaderParser().getSectionHeaderFeatured(doc, documentParts, true);
                List<LayoutToken> tokenizationHeader = Document.getTokenizationParts(documentParts, doc.getTokenizations());
                String labeledResult = null;

                // alternative
                String alternativeHeader = doc.getHeaderFeatured(true, true);
                // we choose the longest header
                if (StringUtils.isNotBlank(StringUtils.trim(header))) {
                    header = alternativeHeader;
                    tokenizationHeader = doc.getTokenizationsHeader();
                } else if (StringUtils.isNotBlank(StringUtils.trim(alternativeHeader)) && alternativeHeader.length() > header.length()) {
                    header = alternativeHeader;
                    tokenizationHeader = doc.getTokenizationsHeader();
                }

                if (StringUtils.isNotBlank(StringUtils.trim(header))) {
                    labeledResult = engine.getParsers().getHeaderParser().label(header);

                    BiblioItem resHeader = new BiblioItem();
                    resHeader.generalResultMapping(doc, labeledResult, tokenizationHeader);

                    // get the LayoutToken of the abstract - all the other ones should be excluded! 
                    List<LayoutToken> abstractTokens = resHeader.getLayoutTokens(TaggingLabels.HEADER_ABSTRACT);

                    if (tokenizationHeader != null) {
                        for(LayoutToken token : tokenizationHeader) {
                            toExclude.add(token.getOffset());
                        }
                    }
                    if (abstractTokens != null) {
                        for(LayoutToken token : abstractTokens) {
                            toExclude.remove(new Integer(token.getOffset()));
                        }
                    }
                }
            }

            // body
            /*documentParts = doc.getDocumentPart(SegmentationLabels.BODY);
            if (documentParts != null) {
                logger.debug("Process body...");
                // full text processing
                Pair<String, LayoutTokenization> featSeg = FullTextParser.getBodyTextFeatured(doc, documentParts);
                if (featSeg != null) {
                    // if featSeg is null, it usually means that no body segment is found in the
                    // document segmentation
                    String bodytext = featSeg.getA();

                    LayoutTokenization tokenizationBody = featSeg.getB();
                    if ((bodytext != null) && (bodytext.trim().length() > 0)) {
                        engine.getParsers().getFullTextParser().label(bodytext);
                    } else {
                        logger.debug("Fulltext model: The input to the CRF processing is empty");
                    }
                }
            }*/

            // we need the tokens of the reference sections (to exclude them!)



            documentParts = doc.getDocumentPart(SegmentationLabels.REFERENCES);
            List<LayoutToken> tokenizationReferences = null;
            if (documentParts != null) {
                tokenizationReferences = Document.getTokenizationParts(documentParts, doc.getTokenizations());
            }

            // and we need the tokens of the running header (to exclude them!)
            documentParts = doc.getDocumentPart(SegmentationLabels.HEADNOTE);
            List<LayoutToken> tokenizationHeadNotes = null;
            if (documentParts != null) {
                tokenizationHeadNotes = Document.getTokenizationParts(documentParts, doc.getTokenizations());
            }
            // and the page number
            documentParts = doc.getDocumentPart(SegmentationLabels.PAGE_NUMBER);
            List<LayoutToken> tokenizationPageNumber = null;
            if (documentParts != null) {
                tokenizationPageNumber = Document.getTokenizationParts(documentParts, doc.getTokenizations());
            }

            // we compile all the remaining indices to be excluded
            if (tokenizationReferences != null) {
                for (LayoutToken token : tokenizationReferences) {
                    toExclude.add(token.getOffset());
                }
            }
            if (tokenizationReferences != null) {
                for (LayoutToken token : tokenizationReferences) {
                    toExclude.add(token.getOffset());
                }
            }
            if (tokenizationPageNumber != null) {
                for (LayoutToken token : tokenizationPageNumber) {
                    toExclude.add(token.getOffset());
                }
            }

            // update total number of mentions
            for(SoftciteAnnotation annotation : localAnnotations) {
                // context
                String context = annotation.getContext();
                if (context == null)
                    continue;

                if ( (annotation.getSoftwareMention() != null) || 
                     (annotation.getVersionNumber() != null) ||
                     (annotation.getVersionDate() != null) ||
                     (annotation.getCreator() != null) ||
                     (annotation.getUrl() != null) ) 
                    totalMentions++;

                if (annotation.getSoftwareMention() != null)
                    totalSoftwareMentions++;
                if (annotation.getVersionNumber() != null)
                    totalVersionNumberMentions++;
                if (annotation.getVersionDate() != null)
                    totalVersionDateMentions++;
                if (annotation.getCreator() != null)
                    totalCreatorMentions++;
                if (annotation.getUrl() != null)
                    totalUrlMentions++;
            }

            // TBD: use a map...
            checkMentionContextMatch(localAnnotations, "software", docName, writerSoftware);
            checkMentionContextMatch(localAnnotations, "version-number", docName, writerVersionNumber);
            checkMentionContextMatch(localAnnotations, "version-date", docName, writerVersionDate);
            checkMentionContextMatch(localAnnotations, "creator", docName, writerCreator);
            checkMentionContextMatch(localAnnotations, "url", docName, writerUrl);

            // now try to align annotations with actual article content
            
            List<LayoutToken> tokens = doc.getTokenizations();
            if (tokens != null) {
                logger.debug("Process content... ");
                //LOGGER.debug(LayoutTokensUtil.toText(titleTokens));
                alignLayoutTokenSequence(document, tokens, localAnnotations);
            }

            List<Annotation> inlineAnnotations = document.getInlineAnnotations();
            if (inlineAnnotations != null) {
               Collections.sort(inlineAnnotations);

                // now we can output an annotated training file for the whole document content
                try {
                    generateAnnotatedXMLDocument(xmlPath + File.separator + new File(pdfFile.getAbsolutePath())
                                                                .getName().replace(".pdf", ".software-mention.xml"),
                                                              doc, 
                                                              inlineAnnotations, 
                                                              entry.getKey(),
                                                              toExclude);
                } catch(Exception e) {
                    logger.error("Failed to write the resulting annotated document in xml", e);
                }
            }
            
        }

        writerSoftware.close();
        writerVersionDate.close();
        writerVersionNumber.close();
        writerCreator.close();
        writerUrl.close();

        System.out.println("\ntotal number of failed article PDF download: " + articleUtilities.totalFail);
        System.out.println("total number of failed article PDF download based on DOI: " + articleUtilities.totalDOIFail + "\n");

        // to be done: use a map...
        System.out.println("\nUnmatched contexts: " + unmatchedContexts + " out of " + totalContexts + " total contexts\n");

        //System.out.println("Unmatched software mentions: " + unmatchedSoftwareMentions + " out of " + totalMentions + " total mentions");
        System.out.println("Unmatched software mentions: " + unmatchedSoftwareMentions + " out of " + 
            totalSoftwareMentions + " total software mentions (" +
            formatPourcent((double)unmatchedSoftwareMentions/totalSoftwareMentions) + ")");

        //System.out.println("Unmatched version number mentions: " + unmatchedVersionNumberMentions + " out of " + totalMentions + " total mentions");
        System.out.println("Unmatched version number mentions: " + unmatchedVersionNumberMentions + " out of " + 
            totalVersionNumberMentions + " total version number mentions (" +
            formatPourcent((double)unmatchedVersionNumberMentions/totalVersionNumberMentions) + ")");

        //System.out.println("Unmatched version date mentions: " + unmatchedVersionDateMentions + " out of " + totalMentions + " total mentions");
        System.out.println("Unmatched version date mentions: " + unmatchedVersionDateMentions + " out of " + 
            totalVersionDateMentions + " total version date mentions (" +
            formatPourcent((double)unmatchedVersionDateMentions/totalVersionDateMentions) + ")");

        //System.out.println("Unmatched creator mentions: " + unmatchedCreatorMentions + " out of " + totalMentions + " total mentions");
        System.out.println("Unmatched creator mentions: " + unmatchedCreatorMentions + " out of " + 
            totalCreatorMentions + " total creator mentions (" +
            formatPourcent((double)unmatchedCreatorMentions/totalCreatorMentions) + ")");

        //System.out.println("Unmatched url mentions: " + unmatchedUrlMentions + " out of " + totalMentions + " total mentions");
        System.out.println("Unmatched url mentions: " + unmatchedUrlMentions + " out of " + totalUrlMentions + " total url mentions (" +
            formatPourcent((double)unmatchedUrlMentions/totalUrlMentions) + ")");

        int unmatchedMentions = unmatchedSoftwareMentions + unmatchedVersionNumberMentions + unmatchedVersionDateMentions +
                                unmatchedCreatorMentions + unmatchedUrlMentions;
        System.out.println("\nTotal unmatched mentions: " + unmatchedMentions + " out of " + totalMentions + " total mentions (" + 
            formatPourcent((double)unmatchedMentions/totalMentions) + ") \n");
    }


    private String formatPourcent(double num) {
        NumberFormat defaultFormat = NumberFormat.getPercentInstance();
        defaultFormat.setMinimumFractionDigits(2);
        return defaultFormat.format(num);
    }

    /**
     * In this method, we check if the mentions provided by an annotation are present in the provided context.
     * If not, we report it for manual correction.
     */
    private void checkMentionContextMatch(List<SoftciteAnnotation> localAnnotations, 
                                        String field, 
                                        String documentId, 
                                        Writer writer) throws IOException, IllegalArgumentException {
        for(SoftciteAnnotation annotation : localAnnotations) {
            // context
            String context = annotation.getContext();
            if (context == null)
                continue;

            String simplifiedContext = CrossAgreement.simplifiedField(context);

            // mention
            String mention = null;

            switch (field) {
                case "software":
                    mention = annotation.getSoftwareMention();
                    break;
                case "version-number":
                    mention = annotation.getVersionNumber();
                    break;
                case "version-date":
                    mention = annotation.getVersionDate();
                    break;
                case "creator":
                    mention = annotation.getCreator();
                    break;
                case "url":
                    mention = annotation.getUrl();
                    break;
                default:
                    throw new IllegalArgumentException("Invalid field: " + field);
            }

            if (mention != null) {
                //System.out.println(type + " mention: " + mention);
                //System.out.println("context: " + context);

                String simplifiedMention = CrossAgreement.simplifiedField(mention);

                if (simplifiedContext.indexOf(simplifiedMention) == -1) {
                    switch (field) {
                        case "software":
                            unmatchedSoftwareMentions++;
                            break;
                        case "version-number":
                            unmatchedVersionNumberMentions++;
                            break;
                        case "version-date":
                            unmatchedVersionDateMentions++;
                            break;
                        case "creator":
                            unmatchedCreatorMentions++;
                            break;
                        case "url":
                            unmatchedUrlMentions++;
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid field: " + field);
                    }
                    
                    context = context.replace("\t", " ").replace("\n", " ");
                    context = context.replaceAll(" +", " ");
                    writer.write(documentId + "\t" + annotation.getIdentifier() + "\t" + field + "\t" + 
                        mention + "\t" + context + "\t" + annotation.getPage());
                    writer.write(System.lineSeparator());
                }
            }
        }
    }

    private void alignLayoutTokenSequence(AnnotatedDocument annotatedDocument, List<LayoutToken> layoutTokens, List<SoftciteAnnotation> localAnnotations) {
        if ( (layoutTokens == null) || (layoutTokens.size() == 0) )
            return;

        List<OffsetPosition> occupiedPositions = new ArrayList<OffsetPosition>();

        int annotationIndex = -1;
        for(SoftciteAnnotation annotation : localAnnotations) {
            annotationIndex++;
            if (annotation.getSoftwareMention() == null)
                continue;
            //totalMentions++;
            if (annotation.getContext() == null)
                continue;
            totalContexts++;

            boolean matchFound = false;
            String softwareMention = annotation.getSoftwareMention();
            FastMatcher matcher = new FastMatcher();
            matcher.loadTerm(softwareMention, GrobidAnalyzer.getInstance(), true);
            // case sensitive matching, ignore standard delimeters
            List<OffsetPosition> positions = matcher.matchLayoutToken(layoutTokens, true, true);

            if ( (positions == null) || (positions.size() == 0) ) {
                //unmatchedMentions++;
                System.out.println("\t\t!!!!!!!!! " + softwareMention + ": failed mention");
                continue;
            }

            int page = annotation.getPage();
            String quote = annotation.getContext();
            for(OffsetPosition position : positions) {
                // check if we are at the right page
                if (layoutTokens.get(position.start).getPage() != page)
                    continue;

                // check if the position already taken
                if (isOverlapping(occupiedPositions, position))
                    continue;

                // check if we are within the quote
                if (!inQuote(position, softwareMention, layoutTokens, quote))
                    continue;

                annotation.setOccurence(position);
                occupiedPositions.add(position);
                matchFound = true;
                System.out.print(softwareMention + ": match position : " + position.toString() + " : ");
                for(int i=position.start; i<=position.end; i++) {
                    System.out.print(layoutTokens.get(i).getText());
                }
                System.out.println("");

                String softwareContent = LayoutTokensUtil.toText(layoutTokens.subList(position.start, position.end+1));

                boolean correspPresent = false;

                // try to get the chunk of text corresponding to the version if any
                String versionNumber = annotation.getVersionNumber();
                if (versionNumber != null) {
                    FastMatcher matcher2 = new FastMatcher();
                    matcher2.loadTerm(versionNumber, GrobidAnalyzer.getInstance(), true);
                    // case sensitive matching, ignore standard delimeters
                    List<OffsetPosition> positions2 = matcher2.matchLayoutToken(layoutTokens, true, true);
                    OffsetPosition positionVersionNumber = null;
                    if (positions2 != null) {
                        for(OffsetPosition position2 : positions2) {
                            if ((positionVersionNumber == null) & Math.abs(position2.start - position.start) < 20)
                                positionVersionNumber = position2;
                            else if (positionVersionNumber != null) {
                                if (Math.abs(position2.start - position.start) < Math.abs(positionVersionNumber.start - position.start))
                                    positionVersionNumber = position2;
                            }
                        }
                    }

                    // annotation for the version number
                    if ( (positionVersionNumber != null) && (!isOverlapping(occupiedPositions, positionVersionNumber)) ) {
                        Annotation versionNumberInlineAnnotation = new Annotation();
                        versionNumberInlineAnnotation.addAttributeValue("type", "version-number");
                        versionNumberInlineAnnotation.addAttributeValue("corresp", "#software-"+annotationIndex);
                        correspPresent = true;
                        versionNumberInlineAnnotation.setText(versionNumber);
                        versionNumberInlineAnnotation.setOccurence(positionVersionNumber);
                        annotatedDocument.addInlineAnnotation(versionNumberInlineAnnotation);
                        occupiedPositions.add(positionVersionNumber);
                    }
                }

                // annotation for the version date
                String versionDate = annotation.getVersionDate();
                if (versionDate != null) {
                    FastMatcher matcher2 = new FastMatcher();
                    matcher2.loadTerm(versionDate, GrobidAnalyzer.getInstance(), true);
                    // case sensitive matching, ignore standard delimeters
                    List<OffsetPosition> positions2 = matcher2.matchLayoutToken(layoutTokens, true, true);
                    OffsetPosition positionVersionDate = null;
                    if (positions2 != null) {
                        for(OffsetPosition position2 : positions2) {
                            if ((positionVersionDate == null) & Math.abs(position2.start - position.start) < 20)
                                positionVersionDate = position2;
                            else if (positionVersionDate != null) {
                                if (Math.abs(position2.start - position.start) < Math.abs(positionVersionDate.start - position.start))
                                    positionVersionDate = position2;
                            }
                        }
                    }

                    // annotation for the version date
                    if ( (positionVersionDate != null) && (!isOverlapping(occupiedPositions, positionVersionDate)) ) {
                        Annotation versionDateInlineAnnotation = new Annotation();
                        versionDateInlineAnnotation.addAttributeValue("type", "version-date");
                        versionDateInlineAnnotation.addAttributeValue("corresp", "#software-"+annotationIndex);
                        correspPresent = true;
                        versionDateInlineAnnotation.setText(versionDate);
                        versionDateInlineAnnotation.setOccurence(positionVersionDate);
                        annotatedDocument.addInlineAnnotation(versionDateInlineAnnotation);
                        occupiedPositions.add(positionVersionDate);
                    }
                }

                // annotation for the creator
                String creator = annotation.getCreator();
                if (creator != null) {
                    FastMatcher matcher2 = new FastMatcher();
                    matcher2.loadTerm(creator, GrobidAnalyzer.getInstance(), true);
                    // case sensitive matching, ignore standard delimeters
                    List<OffsetPosition> positions2 = matcher2.matchLayoutToken(layoutTokens, true, true);
                    OffsetPosition positionCreator = null;
                    if (positions2 != null) {
                        for(OffsetPosition position2 : positions2) {
                            if ((positionCreator == null) & Math.abs(position2.start - position.start) < 20)
                                positionCreator = position2;
                            else if (positionCreator != null) {
                                if (Math.abs(position2.start - position.start) < Math.abs(positionCreator.start - position.start))
                                    positionCreator = position2;
                            }
                        }
                    }

                    // annotation for the creator
                    if ( (positionCreator != null) && (!isOverlapping(occupiedPositions, positionCreator)) ) {
                        Annotation creatorInlineAnnotation = new Annotation();
                        creatorInlineAnnotation.addAttributeValue("type", "creator");
                        creatorInlineAnnotation.addAttributeValue("corresp", "#software-"+annotationIndex);
                        correspPresent = true;
                        creatorInlineAnnotation.setText(creator);
                        creatorInlineAnnotation.setOccurence(positionCreator);
                        annotatedDocument.addInlineAnnotation(creatorInlineAnnotation);
                        occupiedPositions.add(positionCreator);
                    }
                }
                
                // annotation for the url
                String url = annotation.getUrl();
                if (url != null) {
                    FastMatcher matcher2 = new FastMatcher();
                    matcher2.loadTerm(url, GrobidAnalyzer.getInstance(), true);
                    // case sensitive matching, ignore standard delimeters
                    List<OffsetPosition> positions2 = matcher2.matchLayoutToken(layoutTokens, true, true);
                    OffsetPosition positionUrl = null;
                    if (positions2 != null) {
                        for(OffsetPosition position2 : positions2) {
                            if ((positionUrl == null) & Math.abs(position2.start - position.start) < 20)
                                positionUrl = position2;
                            else if (positionUrl != null) {
                                if (Math.abs(position2.start - position.start) < Math.abs(positionUrl.start - position.start))
                                    positionUrl = position2;
                            }
                        }
                    }

                    // annotation for the url
                    if ( (positionUrl != null) && (!isOverlapping(occupiedPositions, positionUrl)) ) {
                        Annotation urlInlineAnnotation = new Annotation();
                        urlInlineAnnotation.addAttributeValue("type", "url");
                        urlInlineAnnotation.addAttributeValue("corresp", "#software-"+annotationIndex);
                        correspPresent = true;
                        urlInlineAnnotation.setText(url);
                        urlInlineAnnotation.setOccurence(positionUrl);
                        annotatedDocument.addInlineAnnotation(urlInlineAnnotation);
                        occupiedPositions.add(positionUrl);
                    }
                }

                // create the inline annotations corresponding to this annotation
                // annotation for the software name
                Annotation softwareInlineAnnotation = new Annotation();
                softwareInlineAnnotation.addAttributeValue("type", "software");
                if (correspPresent)
                    softwareInlineAnnotation.addAttributeValue("id", "software-"+annotationIndex);
                softwareInlineAnnotation.setText(softwareContent);
                softwareInlineAnnotation.setOccurence(position);
                annotatedDocument.addInlineAnnotation(softwareInlineAnnotation);

                break;
            }
            if (!matchFound) {
                //unmatchedMentions++;
                System.out.println("\t\t!!!!!!!!! " + softwareMention + ": failed mention after position check");
            }
        }
    }

    private boolean isOverlapping(List<OffsetPosition> occupiedPositions, OffsetPosition position) {
        for(OffsetPosition occupiedPosition : occupiedPositions) {
            if (occupiedPosition.start == position.start || occupiedPosition.end == position.end)
                return true;
            if (position.start <= occupiedPosition.start && position.end > occupiedPosition.start)
                return true;
            if (position.start >= occupiedPosition.start && occupiedPosition.end >= position.start)
                return true;
        }
        return false;
    }

    private boolean inQuote(OffsetPosition position, String softwareName, List<LayoutToken> layoutTokens, String quote) {
        // actual left context from position
        int leftBound = Math.max(0, position.start-10);
        String leftContext = LayoutTokensUtil.toText(layoutTokens.subList(leftBound, position.start));
        String leftContextSimplified = CrossAgreement.simplifiedField(leftContext);

        // actual right context from position
        int rightBound = Math.min(position.end+11, layoutTokens.size());
        String rightContext = LayoutTokensUtil.toText(layoutTokens.subList(position.end+1, rightBound));
        String rigthContextSimplified = CrossAgreement.simplifiedField(rightContext);

        // now the quote
        String leftQuoteSimplified = null;
        String rightQuoteSimplified = null;

        // matching 
        Pattern mentionPattern = Pattern.compile(Pattern.quote(softwareName.toLowerCase()));
        Matcher mentionMatcher = mentionPattern.matcher(quote.toLowerCase());
        OffsetPosition mentionInQuote = new OffsetPosition();
        if (mentionMatcher.find()) {
            // we found a match :)
            String leftQuote = quote.substring(0, mentionMatcher.start());
            leftQuoteSimplified = CrossAgreement.simplifiedField(leftQuote);

            String rightQuote = quote.substring(mentionMatcher.end(), quote.length());
            rightQuoteSimplified = CrossAgreement.simplifiedField(rightQuote);
        } else {
            // more agressive soft matching 
            String simplifiedContext = CrossAgreement.simplifiedField(quote);
            String softwareNameSimplified = CrossAgreement.simplifiedField(softwareName);

            int ind = simplifiedContext.indexOf(softwareNameSimplified);
            if (ind == -1)
                return false;
            else {
                leftQuoteSimplified = simplifiedContext.substring(0, ind);
                rightQuoteSimplified = simplifiedContext.substring(ind+softwareNameSimplified.length(), simplifiedContext.length());
            }
        }

        System.out.println("Mention: " + LayoutTokensUtil.toText(layoutTokens.subList(position.start, position.end+1)));
        System.out.println("Quote: " + quote);
        System.out.println("leftContextSimplified: " + leftContextSimplified);
        System.out.println("rigthContextSimplified: " + rigthContextSimplified);
        System.out.println("leftQuoteSimplified: " + leftQuoteSimplified);
        System.out.println("rightQuoteSimplified: " + rightQuoteSimplified);

        // it might need to be relaxed, with Ratcliff/Obershelp Matching
        if ( (rigthContextSimplified.startsWith(rightQuoteSimplified) || rightQuoteSimplified.startsWith(rigthContextSimplified)) && 
             (leftContextSimplified.endsWith(leftQuoteSimplified) || leftQuoteSimplified.endsWith(leftContextSimplified)) ) {
            System.out.println("-> match !");
            return true;  
        }
        System.out.println("-> NO match !");
        return false;
    }

    private void generateAnnotatedXMLDocument(String outputFile, 
                                              Document doc, 
                                              List<Annotation> inlineAnnotations, 
                                              String docID,
                                              List<Integer> toExclude) throws IOException, ParsingException {
        Element root = SoftwareParser.getTEIHeader(docID);
        Element textNode = teiElement("text");
        // for the moment we suppose we have english only...
        textNode.addAttribute(new Attribute("xml:lang", "http://www.w3.org/XML/1998/namespace", "en"));

        textNode.appendChild(insertAnnotations(doc.getTokenizations(), toExclude, inlineAnnotations));

        root.appendChild(textNode);
        //System.out.println(XmlBuilderUtils.toXml(root));

        // now some massage to beautify
        String xml = XmlBuilderUtils.toXml(root);
        xml = xml.replace("\n\n", "</p>\n\n<p>");
        xml = xml.replace("<p></text>", "</text>");
        xml = xml.replace("<text xml:lang=\"en\">", "\n<text xml:lang=\"en\">\n");
        xml = xml.replace("<p></p>", "");
        try {
            FileUtils.writeStringToFile(new File(outputFile), xml);
            //FileUtils.writeStringToFile(new File(outputFile), format(root));
        } catch (IOException e) {
            throw new IOException("Cannot create training data because output file can not be accessed: " + outputFile);
        } /*catch (ParsingException e) {
            throw new ParsingException("Cannot create training data because generated XML appears ill-formed");
        }*/
        
    }

    /**
     *  Add XML annotations corresponding to entities in a piece of text, to be included in
     *  generated training data.
     */
    public Element insertAnnotations(List<LayoutToken> tokenizations, 
                                     List<Integer> toExclude, 
                                     List<Annotation> inlineAnnotations) {
        Element p = teiElement("p");

        int pos = 0;
        if ( (inlineAnnotations == null) || (inlineAnnotations.size() == 0) ) {
            //p.appendChild(LayoutTokensUtil.toText(tokenizations));
            p.appendChild(this.toText(tokenizations, toExclude));
            return p;
        }
        System.out.println(inlineAnnotations.size() + " inline annotations");
        for (Annotation annotation : inlineAnnotations) {
            //if (annotation.getType() == SoftciteAnnotation.AnnotationType.SOFTWARE && 
            //    annotation.getOccurence() != null) {
            OffsetPosition position = annotation.getOccurence();
            
            Element entityElement = teiElement("rs");
            Map<String, String> attributes = annotation.getAttributes();
            if (attributes == null)
                continue;
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                entityElement.addAttribute(new Attribute(entry.getKey(), entry.getValue()));
            }

            int startE = position.start;
            int endE = position.end;

            //p.appendChild(LayoutTokensUtil.toText(tokenizations.subList(pos, startE)));
            p.appendChild(this.toText(tokenizations.subList(pos, startE), toExclude));
                
            //entityElement.appendChild(LayoutTokensUtil.toText(tokenizations.subList(startE, endE+1)));
            entityElement.appendChild(annotation.getText());
            pos = endE+1;

            p.appendChild(entityElement);
            //}
        }
        //p.appendChild(LayoutTokensUtil.toText(tokenizations.subList(pos, tokenizations.size())));
        p.appendChild(this.toText(tokenizations.subList(pos, tokenizations.size()), toExclude));

        return p;
    }

    /**
     * This is an ad-hoc serialization for sequence of LayoutToken where we mute some tokens (in our case title and 
     * bibliographical references and running header), following the policy of the softcite scheme. 
     */
    private String toText(List<LayoutToken> tokens, List<Integer> toExclude) {
        StringBuilder builder = new StringBuilder();

        for(LayoutToken token : tokens) {
            if (toExclude.contains(token.getOffset())) 
                continue;
            builder.append(token.getText());
        }

        return builder.toString();
    }

    public static String format(nu.xom.Element root) throws ParsingException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        nu.xom.Serializer serializer = new nu.xom.Serializer(out);
        nu.xom.Document doc = new nu.xom.Document(root);
        serializer.setIndent(4);
        serializer.write(doc);
        return out.toString("UTF-8");
    }

    private void importCSVFiles(String csvPath, Map<String, AnnotatedDocument> documents, Map<String, SoftciteAnnotation> annotations) {
        // process is driven by what's available in the softcite dataset
        File softciteRoot = new File(csvPath);
        // if the given root is the softcite repo root, we go down to data/ and then csv_dataset 
        // (otherwise we assume we are already under data/csv_dataset)
        // todo


        File[] refFiles = softciteRoot.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".csv");
            }
        });

        if (refFiles == null) {
            logger.warn("We found no .csv file to process");
            return;
        }

        // this csv file gives the mention context for each annotation
        File softciteMentions = new File(csvPath + File.separator + "softcite_in_text_mentions.csv");
        
        // this csv file gives the attributes for each mention, including the "string" of mention
        File softciteAttributes = new File(csvPath + File.separator + "softcite_codes_applied.csv");
        
        // this csv file gives information on the bibliographical reference associated to a mention 
        File softciteReferences = new File(csvPath + File.separator + "softcite_references.csv");

        // this csv file gives information on the article set to which each article belongs to 
        File softciteArticles = new File(csvPath + File.separator + "softcite_articles.csv");

        try {
            CSVParser parser = CSVParser.parse(softciteAttributes, UTF_8, CSVFormat.RFC4180);
            // csv fields in this file are as follow
            // selection,coder,code,was_code_present,code_label
            
            // *selection* is an identifier for the text mention, normally we have one per annotation, 
            // but maybe it's a full context passage?
            // *coder* is the id of the annotator
            // *code* is the name of the annotation class/attribute (e.g. software_name, version date)
            // *was_code_present* is a boolean indicating if the annotation class appears in the 
            // "selection" (context passage probably), not sure what is the purpose of this
            // *code_label* is the raw string corresponding to the annotated chunk
            
            boolean start = true;
            for (CSVRecord csvRecord : parser) {
                if (start) {
                    start = false;
                    continue;
                }
                SoftciteAnnotation annotation = null;
                String attribute = null;
                for(int i=0; i<csvRecord.size(); i++) {
                    String value = csvRecord.get(i);
                    if (value.trim().length() == 0)
                        continue;
                    value = cleanValue(value);
                    if (i == 0) {
                        /*if (duplicateAnnotationIdentifiers.contains(value)) {
                            // this record must be ignored for the moment
                            i = csvRecord.size();
                            continue;
                        }*/

                        if (annotations.get(value) == null) {
                            annotation = new SoftciteAnnotation();
                            annotation.setIdentifier(value);
                            annotations.put(value, annotation);
                        } else {
                            annotation = annotations.get(value);
                        }
                    } else if (i == 1) {
                        annotation.setAnnotatorID(value);
                    } else if (i == 2) {
                        attribute = value;
                    } else if (i == 3) {
                        if (attribute.equals("software_was_used")) {
                            if (value.equals("true"))
                                annotation.setIsUsed(true);
                        }
                    } else if (i == 4) {
                        if (attribute.equals("software_name"))
                            annotation.setSoftwareMention(value);
                        else if (attribute.equals("version_number"))
                            annotation.setVersionNumber(value);
                        else if (attribute.equals("version_date"))
                            annotation.setVersionDate(value);
                        else if (attribute.equals("url"))
                            annotation.setUrl(value);
                        else if (attribute.equals("creator"))
                            annotation.setCreator(value);
                        else {
                            logger.warn("unexpected attribute value: " + attribute);
                        }
                    }
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        }

        System.out.println(annotations.size() + " annotations from " + softciteAttributes.getName());

        int nbReferenceAnnotations = 0;
        try {
            CSVParser parser = CSVParser.parse(softciteReferences, UTF_8, CSVFormat.RFC4180);
            // reference,from_in_text_selection,article,quote,coder,page,reference_type
            //iomolecules_in_the_computer:Jmol_to_the_rescue_Herraez_2006,PMC2447781_CT23,PMC2447781,". Herraez,A. (2006) Biomolecules in the computer: Jmol to the rescue.Biochem. Mol. Biol. Educat.,34, 255–261",ctjoe,6,publication
            boolean start = true;
            for (CSVRecord csvRecord : parser) {
                if (start) {
                    start = false;
                    continue;
                }
                SoftciteAnnotation annotation = null;
                AnnotatedDocument document = null;
                for(int i=0; i<csvRecord.size(); i++) {
                    String value = csvRecord.get(i);
                    if (value.trim().length() == 0)
                        continue;
                    value = cleanValue(value);
                    if (i == 0) {
                        /*if (duplicateAnnotationIdentifiers.contains(value)) {
                            // this record must be ignored for the moment
                            i = csvRecord.size();
                            continue;
                        }*/

                        if (annotations.get(value) == null) {
                            annotation = new SoftciteAnnotation();
                            annotation.setIdentifier(value);
                            annotation.setType("reference"); 
                            annotations.put(value, annotation);
                        } else {
                            annotation = annotations.get(value);
                        }
                        nbReferenceAnnotations++;
                    } else if (i == 1) {
                        annotation.setReferedAnnotationMention(value);
                        // add back the bibliographical reference associated to the mention
                        SoftciteAnnotation referredAnnotation = annotations.get(value);
                        if (referredAnnotation == null) {
                            System.out.println("referred annotation not found: " + value);
                        }
                    } else if (i == 2) {
                        String documentID = value;
                        if (documents.get(documentID) == null) {
                            document = new AnnotatedDocument();
                            document.setDocumentID(value);
                            documents.put(value, document);
                        } else 
                            document = documents.get(documentID);
                        document.addAnnotation(annotation);
                    } else if (i == 3) {
                        annotation.setReferenceString(value);
                    } else if (i == 4) {
                        annotation.setAnnotatorID(value);
                    } else if (i == 5) {
                        int intValue = -1;
                        try {
                            intValue = Integer.parseInt(value);
                        } catch(Exception e) {
                            logger.warn("Invalid page number: " + value);
                        }
                        if (intValue != -1)
                            annotation.setPage(intValue);
                    } else if (i == 6) {
                        annotation.setRefType(value.toLowerCase());
                    }
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        }

        System.out.println(nbReferenceAnnotations + " reference annotations from " + softciteReferences.getName());

        int nbMentionAnnotations = 0;
        try {
            CSVParser parser = CSVParser.parse(softciteMentions, UTF_8, CSVFormat.RFC4180);
            // csv fields in this file are as follow
            // selection,coder,article,quote,page,mention_type,certainty,memo
            
            // *selection* is an identifier for the text mention, normally we have one per annotation, 
            // but maybe it's a full context passage?
            // *coder* is the id of the annotator
            // *article* is the nidentifier of the annotated article
            // *quote* is the full passage where something is annotated, as a raw string (a snippet)
            // *page* is the PDF page number where the annotation appears
            // *mention_type* is the type of what is annotated - values listed in the doc are "software", 
            // "algorithm", "hardware" and "other", but actual values appear to be much more diverse 
            // *certainty* is an integer between 1-10 for annotator subjective certainty on the annotation
            // *meno* is a free text field for comments

            boolean start = true;
            int nbCSVlines = 0;
            for (CSVRecord csvRecord : parser) {
                nbCSVlines++;
                if (start) {
                    start = false;
                    continue;
                }
                SoftciteAnnotation annotation = null;
                AnnotatedDocument document = null;
                for(int i=0; i<csvRecord.size(); i++) {
                    String value = csvRecord.get(i);
                    if (value.trim().length() == 0)
                        continue;
                    value = cleanValue(value);
                    if (i == 0) {
                        /*if (duplicateAnnotationIdentifiers.contains(value)) {
                            // this record must be ignored for the moment
                            i = csvRecord.size();
                            continue;
                        }*/

                        if (annotations.get(value) == null) {
                            annotation = new SoftciteAnnotation();
                            annotation.setIdentifier(value);
                            annotations.put(value, annotation);                         
                        } else {
                            annotation = annotations.get(value);
                        }

                        // derive the document ID from the selection - this might not be 
                        // reliable and not genral enough in the future
                        /*int ind = value.indexOf("_");
                        if (ind == -1)
                            continue;
                        String valueDoc = value.substring(0, ind);
                        // filter out non PMC 
                        if (!valueDoc.startsWith("PMC")) 
                            continue;
                        String documentID = valueDoc;
                        if (documents.get(documentID) == null) {
                            document = new AnnotatedDocument();
                            document.setDocumentID(valueDoc);
                            documents.put(valueDoc, document);
                        } else 
                            document = documents.get(documentID);
                        document.addAnnotation(annotation); */

                    } else if (i == 1) {
                        annotation.setAnnotatorID(value);
                    } else if (i == 2) {
                        String documentID = value;
                        if (documents.get(documentID) == null) {
                            document = new AnnotatedDocument();
                            document.setDocumentID(documentID);
                            documents.put(documentID, document);
                        } else 
                            document = documents.get(documentID);
                        document.addAnnotation(annotation); 
                    } else if (i == 3) {
                        annotation.setContext(value);
                        nbMentionAnnotations++;
                    } else if (i == 4) {
                        int intValue = -1;
                        try {
                            intValue = Integer.parseInt(value);
                        } catch(Exception e) {
                            logger.warn("Invalid page number: " + value);
                        }
                        if (intValue != -1)
                            annotation.setPage(intValue);
                    } else if (i == 5) {
                        annotation.setType(value.toLowerCase()); 
                    } else if (i == 6) {
                        int intValue = -1;
                        try {
                            intValue = Integer.parseInt(value);
                        } catch(Exception e) {
                            logger.warn("Invalid certainty value: " + value);
                        }
                        if (intValue != -1)
                            annotation.setCertainty(intValue);
                    } else if (i == 7) {
                        annotation.setMemo(value);
                    }
                }
            }
            System.out.println(nbCSVlines + " csv lines");
        } catch(Exception e) {
            e.printStackTrace();
        }
        System.out.println(nbMentionAnnotations + " mentions annotations from " + softciteMentions.getName());

        try {
            CSVParser parser = CSVParser.parse(softciteArticles, UTF_8, CSVFormat.RFC4180);
            // article,article_set,coder,no_selections_found
            boolean start = true;
            int nbCSVlines = 0;
            for (CSVRecord csvRecord : parser) {
                nbCSVlines++;
                if (start) {
                    start = false;
                    continue;
                }
                AnnotatedDocument document = null;
                for(int i=0; i<csvRecord.size(); i++) {
                    String value = csvRecord.get(i);
                    if (value.trim().length() == 0)
                        continue;
                    value = cleanValue(value);
                    if (i == 0) {
                        String documentID = value;
                        if (documents.get(documentID) == null) {
                            //System.out.println("warning unknown document: " + documentID);
                        } else 
                            document = documents.get(documentID);
                    } else if (i == 1) {
                        String articleSet = value;
                        if (document != null)
                            document.setArticleSet(value);
                    } 
                }
            }
            System.out.println(nbCSVlines + " csv lines");
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    private String cleanValue(String value) {
        value = value.trim();
        value = value.replace("\n", " ");
        if (value.startsWith("\""))
            value = value.substring(1,value.length());
        if (value.endsWith("\""))
            value = value.substring(0,value.length()-1);
        value = value.replaceAll(" +", " ");
        return value.trim();
    }

    /**
     * Access a PDF in a directory:
     * - if present, return the path of the PDF
     * - if not present download the OA PDF, store it in the repo and 
     *   return the path of the local downloaded PDF
     *
     * If PDF not available, return null
     */
    private File getPDF(String pathPDFs, String identifier) {
        File inRepo = new File(pathPDFs + File.separator + identifier + ".pdf");
        if (!inRepo.exists()) {
            File notInRepo = articleUtilities.getPDFDoc(identifier);
            if (notInRepo == null) {
                return null;
            } else {
                // we move the file in the repo of local PDFs
                try {
                    //inRepo = new File(pathPDFs + File.separator + identifier + ".pdf");
                    Files.move(notInRepo.toPath(), inRepo.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    
                } catch(Exception e) {
                    e.printStackTrace();
                    return null;
                }
                return inRepo;
            }
        } else 
            return inRepo;
    }


    /**
     * Command line execution.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
       
        // we are expecting three arguments, absolute path to the original PDF 
        // documents, absolute path to the softcite data in csv and abolute path
        // where to put the generated XML files

        if (args.length != 3) {
            System.err.println("Usage: command [absolute path to the original PDFs] [absolute path to the softcite root data in csv] [output for the generated XML files]");
            System.exit(-1);
        }

        String documentPath = args[0];
        File f = new File(documentPath);
        if (!f.exists() || !f.isDirectory()) {
            System.err.println("path to PDFs directory does not exist or is invalid: " + documentPath);
            System.exit(-1);
        }

        String csvPath = args[1];
        f = new File(csvPath);
        if (!f.exists() || !f.isDirectory()) {
            System.err.println("path to softcite data csv directory does not exist or is invalid: " + csvPath);
            System.exit(-1);
        }

        String xmlPath = args[2];
        f = new File(xmlPath);
        if (!f.exists() || !f.isDirectory()) {
            System.out.println("XML output directory path does not exist, so it will be created");
            new File(xmlPath).mkdirs();
        }       

        AnnotatedCorpusGeneratorCSV converter = new AnnotatedCorpusGeneratorCSV();
        try {
            converter.process(documentPath, csvPath, xmlPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}