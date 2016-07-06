# Author: Aditya Arora, adityaarora@ekstepplus.org

import zipfile
import os
import shutil
import codecs
import json
#Pass as a commandline argument later on
root=os.path.dirname(os.path.abspath(__file__))
ekstep='/'.join(root.split('/')[:-2])
import sys
sys.path.insert(0, os.path.join(ekstep,'Utils'))#Insert at front of list ensuring that our util is executed first in 
#To find files with a particular substring
from findFiles import findFiles

#Extracts all zipfiles into the download directory of that piece of content and deletes the zip files after extraction
def unzip_files(directory):
	assert type(directory)==unicode or type(directory)==str
	#Finds all files in a directory that are of type .zip
	zip_list=findFiles(directory,['.zip'])
	bugs={}
	for zip_file in zip_list:
		#In case zipfile is bad
		try:
			#Extract zip file
			with zipfile.ZipFile(zip_file, 'r') as z:
				z.extractall(directory)
			#Delete zip file after extraction
			os.remove(zip_file)
		except:
			#Can return bugs if you want list of buggy zip files
			bugs.append(zip_file)
			{}


#Transfer the files in assets,data,items and the ecml files
def copy_main_folders(root,identifier):
	assert type(identifier)==unicode or type(identifier)==str
	assert type(root)==unicode or type(root)==str
	#List of files to be copied (To flatten directory structure)
	file_list=findFiles(os.path.join(root,'temp'+identifier),['asset','data','item','ecml'])
	path=os.path.join(root,identifier)
	#To make the new directory in which files will be eventually stored
	if not os.path.exists(path):
		os.makedirs(path)
	#To make the new sub-directories in which the files will be eventually stores
	location=[os.path.join(path,folder) for folder in ['assets','data','items']]
	for loc in location:
		if not os.path.exists(loc):
			os.makedirs(loc)
	#Copying files
	for f in file_list:
		if(f.find('asset')>=0):
			shutil.copy(f,os.path.join(path,'assets'))
		elif(f.find('data')>=0):
			shutil.copy(f,os.path.join(path,'data'))
		elif(f.find('item')>=0):
			shutil.copy(f,os.path.join(path,'items'))
		else:
			shutil.copy(f,path)
	#Delete the messy download directory
	shutil.rmtree(os.path.join(root,'temp'+identifier))

#Adds a manifest.json file for the given piece of content
def add_manifest(obj,directory):
	assert type(obj)==dict
	assert type(directory)==unicode or type(directory)==str
	with codecs.open(os.path.join(directory,'manifest.json'),'w',encoding='utf8') as f:		
		json.dump(obj, f, sort_keys=True, indent=4)
	f.close()