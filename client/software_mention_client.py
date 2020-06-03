import sys
import os
import shutil
import json
import pickle
import lmdb
import argparse
import time
import datetime
import S3
import concurrent.futures
import requests
import pymongo
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib

map_size = 100 * 1024 * 1024 * 1024 

# default endpoint
endpoint_pdf = '/service/annotateSoftwarePDF'
endpoint_txt = '/service/annotateSoftwareText'

class software_mention_client(object):
    """
    Python client for using the GROBID software mention service. 
    """

    def __init__(self, config_path='./config.json'):
        self.config = None
        
        # standard lmdb environment for keeping track of PDF annotation relative to software processing
        self.env_software = None

        self._load_config(config_path)
        self._init_lmdb()

        if self.config['bucket_name'] is not None and len(self.config['bucket_name']) > 0:
            self.s3 = S3.S3(self.config)

        self.mongo_db = None

    def _load_config(self, path='./config.json'):
        """
        Load the json configuration 
        """
        config_json = open(path).read()
        self.config = json.loads(config_json)

    def service_isalive(self):
        # test if GROBID software mention recognizer is up and running...
        the_url = _grobid_software_url(self.config['software_mention_host'], self.config['software_mention_port'])
        the_url += "isalive"
        try:
            r = requests.get(the_url)
            if r.status_code != 200:
                print('Grobid software mention server does not appear up and running ' + str(r.status_code))
            else:
                print("Grobid software mention server is up and running")
                return True
        except: 
            print('Grobid software mention server does not appear up and running:',
                'test call to grobid software mention failed, please check and re-start a server.')
        return False

    def _init_lmdb(self):
        # open in write mode
        envFilePath = os.path.join(self.config["data_path"], 'entries_software')
        self.env_software = lmdb.open(envFilePath, map_size=map_size)

        #envFilePath = os.path.join(self.config["data_path"], 'fail_software')
        #self.env_fail_software = lmdb.open(envFilePath, map_size=map_size)

    def annotate_directory(self, directory):
        # recursive directory walk for all pdf documents
        pdf_files = []
        out_files = []
        full_records = []
        for root, directories, filenames in os.walk(directory):
            for filename in filenames: 
                if filename.endswith(".pdf") or filename.endswith(".PDF"):
                    #print(os.path.join(root,filename))

                    if filename.endswith(".pdf"):
                        filename_json = filename.replace(".pdf", ".software.json")
                    elif filename.endswith(".PDF"):
                        filename_json = filename.replace(".PDF", ".software.json")

                    sha1 = getSHA1(os.path.join(root,filename))

                    # if the json file already exists, we skip 
                    if os.path.isfile(os.path.join(root, filename_json)):
                        # check that this id is considered in the lmdb keeping track of the process
                        with self.env_software.begin() as txn:
                            status = txn.get(sha1.encode(encoding='UTF-8'))
                        if status is None:
                            with self.env_software.begin(write=True) as txn2:
                                txn2.put(sha1.encode(encoding='UTF-8'), "True".encode(encoding='UTF-8')) 
                        continue

                    # if identifier already processed successfully in the local lmdb, we skip
                    # the hash of the PDF file is used as unique identifier for the PDF (SHA1)
                    with self.env_software.begin() as txn:
                        status = txn.get(sha1.encode(encoding='UTF-8'))
                        if status is not None and status.decode(encoding='UTF-8') == "True":
                            continue

                    pdf_files.append(os.path.join(root,filename))
                    if filename.endswith(".pdf"):
                        out_file = filename.replace(".pdf", ".software.json")
                    if filename.endswith(".PDF"):
                        out_file = filename.replace(".PDF", ".software.json")    
                    out_files.append(os.path.join(root,out_file))
                    record = {}
                    record["id"] = sha1
                    full_records.append(record)
                    if len(pdf_files) == self.config["batch_size"]:
                        self.annotate_batch(pdf_files, out_files, full_records)
                        pdf_files = []
                        out_files = []
                        full_records = []
        # last batch
        if len(pdf_files) > 0:
            self.annotate_batch(pdf_files, out_files, full_records)

    def annotate_collection(self, data_path):
        # init lmdb transactions
        # open in read mode
        #print(os.path.join(data_path, 'entries_software'))
        envFilePath = os.path.join(data_path, 'entries')
        self.env = lmdb.open(envFilePath, map_size=map_size)

        with self.env.begin(write=True) as txn:
            nb_total = txn.stat()['entries']
        print("number of entries to process:", nb_total, "entries")

        # iterate over the entries in lmdb
        pdf_files = []
        out_files = []
        full_records = []
        i = 0
        with self.env.begin(write=True) as txn:
            cursor = txn.cursor()
            for key, value in cursor:
                local_entry = _deserialize_pickle(value)
                local_entry["id"] = key.decode(encoding='UTF-8');
                #print(local_entry)

                # if the json file already exists, we skip 
                if os.path.isfile(os.path.join(os.path.join(data_path, generateS3Path(local_entry['id']), local_entry['id']+".software.json"))):
                    # check that this id is considered in the lmdb keeping track of the process
                    with self.env_software.begin() as txn:
                        status = txn.get(local_entry['id'].encode(encoding='UTF-8'))
                    if status is None:
                        with self.env_software.begin(write=True) as txn2:
                            txn2.put(local_entry['id'].encode(encoding='UTF-8'), "True".encode(encoding='UTF-8')) 
                    continue

                # if identifier already processed successfully in the local lmdb, we skip
                with self.env_software.begin() as txn:
                    status = txn.get(local_entry['id'].encode(encoding='UTF-8'))
                    if status is not None and status.decode(encoding='UTF-8') == "True":
                        continue

                pdf_files.append(os.path.join(data_path, generateS3Path(local_entry['id']), local_entry['id']+".pdf"))
                out_files.append(os.path.join(data_path, generateS3Path(local_entry['id']), local_entry['id']+".software.json"))

                full_records.append(local_entry)
                i += 1

                if i == self.config["batch_size"]:
                    self.annotate_batch(pdf_files, out_files, full_records)
                    pdf_files = []
                    out_files = []
                    full_records = []
                    i = 0

        # last batch
        if len(pdf_files) > 0:
            self.annotate_batch(pdf_files, out_files, full_records)
        self.env.close()

    def annotate_batch(self, pdf_files, out_files=None, full_records=None):
        # process a provided list of PDF
        #print("annotate_batch", len(pdf_files))
        with ThreadPoolExecutor(max_workers=self.config["concurrency"]) as executor:
            executor.map(self.annotate, pdf_files, out_files, full_records)

    def reprocess_failed(self):
        """
        we reprocess only files which lead to a failure of the service, we don't reprocess documents
        where no software mention has been found 
        """
        pdf_files = []
        out_files = []
        full_records = []
        i = 0
        with self.env_software.begin() as txn:
            cursor = txn.cursor()
            for key, value in cursor:
                nb_total += 1
                result = value.decode(encoding='UTF-8')
                if result == "False":
                    # reprocess
                    pdf_files.append(os.path.join(data_path, generateS3Path(local_entry['id']), local_entry['id']+".pdf"))
                    out_files.append(os.path.join(data_path, generateS3Path(local_entry['id']), local_entry['id']+".software.json"))
                    # TBD get the full record from the data_path env
                    full_records.append(None)
                    i += 1

            if i == self.config["batch_size"]:
                self.annotate_batch(pdf_files, out_files, full_records)
                pdf_files = []
                out_files = []
                full_records = []
                i = 0

        # last batch
        if len(pdf_files) > 0:
            self.annotate_batch(pdf_files, out_files, full_records)

    def reset(self):
        """
        Remove the local lmdb keeping track of the state of advancement of the annotation and
        of the failed entries
        """
        # close environments
        self.env_software.close()

        envFilePath = os.path.join(self.config["data_path"], 'entries_software')
        shutil.rmtree(envFilePath)

        # re-init the environments
        self._init_lmdb()

    def load_mongo(self, directory):
        for root, directories, filenames in os.walk(directory):
            for filename in filenames: 
                if filename.endswith(".software.json"):
                    if self.config["mongo_host"] is not None:
                        # we store the result in mongo db 
                        if self.mongo_db is None:
                            mongo_client = pymongo.MongoClient(self.config["mongo_host"], int(self.config["mongo_port"]))
                            mongo_db = mongo_client[self.config["mongo_db"]]
                        the_json = open(os.path.join(root,filename)).read()
                        jsonObject = json.loads(the_json)
                        inserted_id = mongo_db.annotations.insert_one(jsonObject).inserted_id
                        #print("inserted annotations with id", inserted_id)

    #def annotate(self, file_in, config, mongo_db, file_out=None, full_record=None, env_software=None):
    def annotate(self, file_in, file_out, full_record):
        the_file = {'input': open(file_in, 'rb')}
        url = "http://" + self.config["software_mention_host"]
        if self.config["software_mention_port"] is not None:
            url += ":" + str(self.config["software_mention_port"])
        url += endpoint_pdf
        
        #print("calling... ", url)

        response = requests.post(url, files=the_file)
        jsonObject = None
        if response.status_code == 503:
            print('service overloaded, sleep', self.config['sleep_time'], seconds)
            time.sleep(self.config['sleep_time'])
            return annotate(file_in, self.config, file_out, full_record)
        elif response.status_code >= 500:
            print('[{0}] Server Error'.format(response.status_code))
        elif response.status_code == 404:
            print('[{0}] URL not found: [{1}]'.format(response.status_code,api_url))
        elif response.status_code >= 400:
            print('[{0}] Bad Request'.format(response.status_code))
            print(response.content )
        elif response.status_code == 200:
            #print('softcite succeed')
            jsonObject = response.json()
        else:
            print('Unexpected Error: [HTTP {0}]: Content: {1}'.format(response.status_code, response.content))

        if jsonObject is not None and len(jsonObject['mentions']) != 0:
            # add file, DOI, date and version info in the JSON, if available
            if full_record is not None:
                jsonObject['id'] = full_record['id']
                if len(full_record) > 1:
                    jsonObject['metadata'] = full_record;
            jsonObject['original_file_path'] = file_in
            jsonObject['file_name'] = os.path.basename(file_in)
            
            if file_out is not None: 
                # we write the json result into a file together with the processed pdf
                with open(file_out, "w", encoding="utf-8") as json_file:
                    json_file.write(json.dumps(jsonObject))

            if config["mongo_host"] is not None:
                # we store the result in mongo db 
                if self.mongo_db is None:
                    mongo_client = pymongo.MongoClient(self.config["mongo_host"], int(self.config["mongo_port"]))
                    self.mongo_db = mongo_client[self.config["mongo_db"]]
                inserted_id = self.mongo_db.annotations.insert_one(jsonObject).inserted_id
                #print("inserted annotations with id", inserted_id)

        # for keeping track of the processing
        # update processed entry in the lmdb (having entities or not) and failure
        if self.env_software is not None and full_record is not None:
            with self.env_software.begin(write=True) as txn:
                if jsonObject is not None:
                    txn.put(full_record['id'].encode(encoding='UTF-8'), "True".encode(encoding='UTF-8')) 
                else:
                    txn.put(full_record['id'].encode(encoding='UTF-8'), "False".encode(encoding='UTF-8'))

    def diagnostic(self):
        """
        Print a report on failures stored during the harvesting process
        """
        nb_total = 0
        nb_fail = 0
        nb_success = 0  

        with self.env_software.begin() as txn:
            cursor = txn.cursor()
            for key, value in cursor:
                nb_total += 1
                result = value.decode(encoding='UTF-8')
                if result == "True":
                    nb_success += 1
                else:
                    nb_fail += 1

        print("---")
        print("total entries:", nb_total)
        print("---")
        print("total successfully processed:", nb_success)
        print("---")
        print("total failed:", nb_fail)
        print("---")

def generateS3Path(identifier):
    '''
    Convert a file name into a path with file prefix as directory paths:
    123456789 -> 12/34/56/123456789
    '''
    return os.path.join(identifier[:2], identifier[2:4], identifier[4:6], identifier[6:8], "")

def _deserialize_pickle(serialized):
    return pickle.loads(serialized)

def _grobid_software_url(grobid_base, grobid_port):
    the_url = 'http://'+grobid_base
    if grobid_port is not None and len(grobid_port)>0:
        the_url += ":"+grobid_port
    the_url += "/service/"
    return the_url

BUF_SIZE = 65536    

def getSHA1(the_file):
    sha1 = hashlib.sha1()

    with open(the_file, 'rb') as f:
        while True:
            data = f.read(BUF_SIZE)
            if not data:
                break
            sha1.update(data)

    #print("SHA1: {0}".format(sha1.hexdigest()))
    return sha1.hexdigest()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description = "GROBID Software Mention recognition client")
    parser.add_argument("--repo-in", default=None, help="path to a directory of PDF files to be processed by the GROBID software mention recognizer")  
    parser.add_argument("--file-in", default=None, help="a single PDF input file to be processed by the GROBID software mention recognizer") 
    parser.add_argument("--file-out", default=None, help="path to a single output the software mentions in JSON format, extracted from the PDF file-in") 
    parser.add_argument("--data-path", default=None, help="path to the JSON dump file created by biblio-glutton-harvester") 
    parser.add_argument("--config", default="./config.json", help="path to the config file, default is ./config.json") 
    parser.add_argument("--reprocess", action="store_true", help="reprocessed failed PDF") 
    parser.add_argument("--reset", action="store_true", help="ignore previous processing states and re-init the annotation process from the beginning") 
    parser.add_argument("--load", action="store_true", help="load json files into the MongoDB instance, the --repo-in parameter must indicate the path "
        +"to the directory of resulting json files to be loaded") 

    args = parser.parse_args()

    data_path = args.data_path
    config_path = args.config
    reprocess = args.reprocess
    reset = args.reset
    file_in = args.file_in
    file_out = args.file_out
    repo_in = args.repo_in
    load_mongo = args.load

    client = software_mention_client(config_path=config_path)

    if not client.service_isalive():
        sys.exit("Grobid software mention service not available, leaving...")

    if reset:
        client.reset()

    if load_mongo:
        # check a mongodb server is specified in the config
        if client.config["mongo_host"] is None or len(client.config["mongo_host"]) == 0:
            sys.exit("the mongodb server where to load the json files is not indicated in the config file, leaving...")
        if repo_in is None: 
            sys.exit("the repo_in where to find the json files to be loaded is not indicated, leaving...")
        client.load_mongo(repo_in)
        
    elif reprocess:
        client.reprocess_failed()
    elif repo_in is not None: 
        client.annotate_directory(repo_in)
    elif file_in is not None:
        annotate(file_in, client.config, file_out)
    elif data_path is not None: 
        client.annotate_collection(data_path)

    client.diagnostic()
    
    
