'''
Split corpus file into collections.

For splitting positive example files, you can also use the .sh script which is using XSLT applied on
the full SoftCite corpus. XSLT works only when the collection is given at TEI level by @subtype

Usage example:

* splitting negative example corpus file:

> python3 splitByCollection.py --tei-corpus ../resources/dataset/software/corpus/softcite_corpus-full.tei.xml 
--examples-file ../resources/dataset/software/corpus/softcite.all.negative.extended.working.tei.xml

* splitting holdout file:

python3 splitByCollection.py --tei-corpus ../resources/dataset/software/corpus/softcite_corpus-full.tei.xml 
--examples-file ../resources/dataset/software/evaluation/softcite_corpus-full.holdout-complete.tei.xml



'''

import os
import argparse
import ntpath
import xml
from lxml import etree
import math
import csv

def split_by_collections(tei_corpus_path, negative_examples_file_path):
    # create map id to collections
    map_identifiers = {}
    collections = []
    # the final softcite corpus 
    root_softcite = etree.parse(tei_corpus_path)
    documents = root_softcite.xpath('//tei:TEI', namespaces={'tei': 'http://www.tei-c.org/ns/1.0'})
    for doc in documents:
        # get the collection information @subtype
        local_collections = doc.xpath('./@subtype')
        if len(local_collections) != 1:
            continue
        collection = local_collections[0]

        # get document identifier under <teiHeader><fileDesc xml:id="b991b33626">
        local_id = doc.xpath('./tei:teiHeader/tei:fileDesc/@xml:id', namespaces={'tei': 'http://www.tei-c.org/ns/1.0'})

        map_identifiers[local_id[0]] = collection
        if not collection in collections:
            collections.append(collection)
    
    # split those negative guys
    for collection in collections:

        root_negatives = etree.parse(negative_examples_file_path)
        node_to_remove = []
        documents = root_negatives.xpath('//tei:TEI', namespaces={'tei': 'http://www.tei-c.org/ns/1.0'})
        for doc in documents:
            # get document identifier under <teiHeader><fileDesc xml:id="b991b33626">
            local_id = doc.xpath('./tei:teiHeader/tei:fileDesc/@xml:id', namespaces={'tei': 'http://www.tei-c.org/ns/1.0'})
            if not local_id[0] in map_identifiers or map_identifiers[local_id[0]] != collection:
                node_to_remove.append(doc)

        for node in node_to_remove:
            node.getparent().remove(node)

        root_negatives.write(negative_examples_file_path.replace(".tei.xml", "." + collection + ".tei.xml"), pretty_print=True)

if __name__ == "__main__":
    parser = argparse.ArgumentParser( 
        description = "Split example TEI corpus file into collections")
    parser.add_argument("--tei-corpus", type=str, help="path to the directory of full text TEI XML files")
    parser.add_argument("--examples-file", type=str, help="path to the XML file containing the set of examples to split")

    args = parser.parse_args()
    tei_corpus_path = args.tei_corpus
    examples_file_path = args.examples_file

    # check path and call methods
    if tei_corpus_path is not None and not os.path.isfile(tei_corpus_path):
        print("the path to the directory of TEI files is not valid: ", tei_corpus_path)
        exit()
    elif examples_file_path is not None and not os.path.isfile(examples_file_path):
        print("the path to the example files is not valid: ", examples_file_path)
        exit()
    else:
        split_by_collections(tei_corpus_path, examples_file_path)


