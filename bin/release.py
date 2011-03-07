#!/usr/bin/python
import re
import sys
import os
import subprocess
import shutil
from datetime import *
from multiprocessing import Process
from utils import *

try:
  from xml.etree.ElementTree import ElementTree
except:
  prettyprint('''
        Welcome to the Infinispan Release Script.
        This release script requires that you use at least Python 2.5.0.  It appears
        that you do not thave the ElementTree XML APIs available, which are available
        by default in Python 2.5.0.
        ''', Levels.FATAL)
  sys.exit(1)

modules = []
uploader = None
git = None

def get_modules(directory):
    '''Analyses the pom.xml file and extracts declared modules'''
    tree = ElementTree()
    f = directory + "/pom.xml"
    if settings['verbose']:
      print "Parsing %s to get a list of modules in project" % f
    tree.parse(f)        
    mods = tree.findall(".//{%s}module" % maven_pom_xml_namespace)
    for m in mods:
        modules.append(m.text)

def help_and_exit():
    prettyprint('''
        Welcome to the Infinispan Release Script.
        
%s        Usage:%s
        
            $ bin/release.py <version> <branch to tag from>
            
%s        E.g.,%s
        
            $ bin/release.py 4.1.1.BETA1 %s<-- this will tag off master.%s
            
            $ bin/release.py 4.1.1.BETA1 4.1.x %s<-- this will use the appropriate branch.%s
            
    ''' % (Colors.yellow(), Colors.end_color(), Colors.yellow(), Colors.end_color(), Colors.green(), Colors.end_color(), Colors.green(), Colors.end_color()), Levels.INFO)
    sys.exit(0)

def validate_version(version):  
  version_pattern = get_version_pattern()
  if version_pattern.match(version):
    return version.strip().upper()
  else:
    prettyprint("Invalid version '"+version+"'!\n", Levels.FATAL)
    help_and_exit()

def tag_release(version, branch):
  if git.remote_branch_exists():
    git.switch_to_branch()
    git.create_tag_branch()
  else:
    prettyprint("Branch %s cannot be found on upstream repository.  Aborting!" % branch, Levels.FATAL)
    sys.exit(100)

def get_project_version_tag(tree):
  return tree.find("./{%s}version" % (maven_pom_xml_namespace))

def get_parent_version_tag(tree):
  return tree.find("./{%s}parent/{%s}version" % (maven_pom_xml_namespace, maven_pom_xml_namespace))

def get_properties_version_tag(tree):
  return tree.find("./{%s}properties/{%s}project-version" % (maven_pom_xml_namespace, maven_pom_xml_namespace))

def write_pom(tree, pom_file):
  tree.write("tmp.xml", 'UTF-8')
  in_f = open("tmp.xml")
  out_f = open(pom_file, "w")
  try:
    for l in in_f:
      newstr = l.replace("ns0:", "").replace(":ns0", "").replace("ns1", "xsi")
      out_f.write(newstr)
  finally:
    in_f.close()
    out_f.close()
    os.remove("tmp.xml")    
  if settings['verbose']:
    prettyprint(" ... updated %s" % pom_file, Levels.INFO)

def patch(pom_file, version):
  '''Updates the version in a POM file.  We need to locate //project/parent/version, //project/version and 
  //project/properties/project-version and replace the contents of these with the new version'''
  if settings['verbose']:
    prettyprint("Patching %s" % pom_file, Levels.DEBUG)
  tree = ElementTree()
  tree.parse(pom_file)    
  need_to_write = False
  
  tags = []
  tags.append(get_parent_version_tag(tree))
  tags.append(get_project_version_tag(tree))
  tags.append(get_properties_version_tag(tree))
  
  for tag in tags:
    if tag != None:
      if settings['verbose']:
        prettyprint("%s is %s.  Setting to %s" % (str(tag), tag.text, version), Levels.DEBUG)
      tag.text=version
      need_to_write = True
    
  if need_to_write:
    # write to file again!
    write_pom(tree, pom_file)
    return True
  else:
    if settings['verbose']:
      prettyprint("File doesn't need updating; nothing replaced!", Levels.DEBUG)
    return False

def get_poms_to_patch(working_dir):
  get_modules(working_dir)
  if settings['verbose']:
    prettyprint('Available modules are ' + str(modules), Levels.DEBUG)
  poms_to_patch = [working_dir + "/pom.xml"]
  for m in modules:
    poms_to_patch.append(working_dir + "/" + m + "/pom.xml")
    # Look for additional POMs that are not directly referenced!
  for additionalPom in GlobDirectoryWalker(working_dir, 'pom.xml'):
    if additionalPom not in poms_to_patch:
      poms_to_patch.append(additionalPom)
      
  return poms_to_patch

def update_versions(version):
  poms_to_patch = get_poms_to_patch(".")
  
  modified_files = []
  for pom in poms_to_patch:
    if patch(pom, version):
      modified_files.append(pom)
  
  ## Now look for Version.java
  version_bytes = '{'
  for ch in version:
    if not ch == ".":
      version_bytes += "'%s', " % ch
  version_bytes = version_bytes[:-2]
  version_bytes += "}"
  version_java = "./core/src/main/java/org/infinispan/Version.java"
  modified_files.append(version_java)
  
  f_in = open(version_java)
  f_out = open(version_java+".tmp", "w")
  try:
    for l in f_in:
      if l.find("static final byte[] version_id = ") > -1:
        l = re.sub('version_id = .*;', 'version_id = ' + version_bytes + ';', l)
      else:
        if l.find("public static final String version =") > -1:
          ver_bits = version.split('.')
          micro_mod = ".%s.%s" % (ver_bits[2], ver_bits[3])
          l = re.sub('version\s*=\s*major\s*\+\s*"[A-Z0-9\.\-]*";', 'version = major + "' + micro_mod + '";', l)
      f_out.write(l)
  finally:
    f_in.close()
    f_out.close()
    
  os.rename(version_java+".tmp", version_java)
  
  # Now make sure this goes back into the repository.
  git.commit(modified_files)

def get_module_name(pom_file):
  tree = ElementTree()
  tree.parse(pom_file)
  return tree.findtext("./{%s}artifactId" % maven_pom_xml_namespace)


def upload_artifacts_to_sourceforge(base_dir, version):
  shutil.rmtree(".tmp", ignore_errors = True)  
  os.mkdir(".tmp")
  os.mkdir(".tmp/%s" % version)
  os.chdir(".tmp")
  dist_dir = "%s/target/distribution" % base_dir
  prettyprint("Copying from %s to %s" % (dist_dir, version), Levels.INFO)
  for item in os.listdir(dist_dir):
    full_name = "%s/%s" % (dist_dir, item)
    if item.strip().lower().endswith(".zip") and os.path.isfile(full_name):      
      shutil.copy2(full_name, version)
  uploader.upload_scp(version, "sourceforge_frs:/home/frs/project/i/in/infinispan/infinispan")
  shutil.rmtree(".tmp", ignore_errors = True)  

def unzip_archive(version):
  os.chdir("./target/distribution")
  ## Grab the distribution archive and un-arch it
  shutil.rmtree("infinispan-%s" % version, ignore_errors = True)
  if settings['verbose']:
    subprocess.check_call(["unzip", "infinispan-%s-all.zip" % version])
  else:
    subprocess.check_call(["unzip", "-q", "infinispan-%s-all.zip" % version])

def upload_javadocs(base_dir, version):
  """Javadocs get rsync'ed to filemgmt.jboss.org, in the docs_htdocs/infinispan directory"""
  version_short = get_version_major_minor(version)
  
  os.chdir("%s/target/distribution/infinispan-%s/doc" % (base_dir, version))
  ## "Fix" the docs to use the appropriate analytics tracker ID
  subprocess.check_call(["%s/bin/updateTracker.sh" % base_dir])
  os.mkdir(version_short)
  os.rename("apidocs", "%s/apidocs" % version_short)
  
  ## rsync this stuff to filemgmt.jboss.org
  uploader.upload_rsync(version_short, "infinispan@filemgmt.jboss.org:/docs_htdocs/infinispan", flags = ['-rv', '--protocol=28'])
  os.chdir(base_dir)

def upload_schema(base_dir, version):
  """Schema gets rsync'ed to filemgmt.jboss.org, in the docs_htdocs/infinispan/schemas directory"""
  os.chdir("%s/target/distribution/infinispan-%s/etc/schema" % (base_dir, version))
  
  ## rsync this stuff to filemgmt.jboss.org
  uploader.upload_rsync('.', "infinispan@filemgmt.jboss.org:/docs_htdocs/infinispan/schemas", flags = ['-rv', '--protocol=28'])
  os.chdir(base_dir)

def do_task(target, args, async_processes):
  if settings['multi_threaded']:
    async_processes.append(Process(target = target, args = args))  
  else:
    target(*args)

### This is the starting place for this script.
def release():
  global settings
  global uploader
  global git
  assert_python_minimum_version(2, 5)
  require_settings_file()
    
  # We start by determining whether the version passed in is a valid one
  if len(sys.argv) < 2:
    help_and_exit()
  
  base_dir = os.getcwd()
  version = validate_version(sys.argv[1])
  branch = "master"
  if len(sys.argv) > 2:
    branch = sys.argv[2]
    
  prettyprint("Releasing Infinispan version %s from branch '%s'" % (version, branch), Levels.INFO)
  sure = input_with_default("Are you sure you want to continue?", "N")
  if not sure.upper().startswith("Y"):
    prettyprint("... User Abort!", Levels.WARNING)
    sys.exit(1)
  prettyprint("OK, releasing! Please stand by ...", Levels.INFO)
  
  ## Set up network interactive tools
  if settings['dry_run']:
    # Use stubs
    prettyprint("*** This is a DRY RUN.  No changes will be committed.  Used to test this release script only. ***", Levels.DEBUG)
    prettyprint("Your settings are %s" % settings, Levels.DEBUG)
    uploader = DryRunUploader()
  else:
    uploader = Uploader()
  
  git = Git(branch, version.upper())
  if not git.is_upstream_clone():
    proceed = input_with_default('This is not a clone of an %supstream%s Infinispan repository! Are you sure you want to proceed?' % (Colors.UNDERLINE, Colors.END), 'N')
    if not proceed.upper().startswith('Y'):
      prettyprint("... User Abort!", Levels.WARNING)
      sys.exit(1)
      
  ## Release order:
  # Step 1: Tag in Git
  prettyprint("Step 1: Tagging %s in git as %s" % (branch, version), Levels.INFO)
  tag_release(version, branch)
  prettyprint("Step 1: Complete", Levels.INFO)
  
  # Step 2: Update version in tagged files
  prettyprint("Step 2: Updating version number in source files", Levels.INFO)
  update_versions(version)
  prettyprint("Step 2: Complete", Levels.INFO)
  
  # Step 3: Build and test in Maven2
  prettyprint("Step 3: Build and test in Maven2", Levels.INFO)
  maven_build_distribution(version)
  prettyprint("Step 3: Complete", Levels.INFO)
  
  async_processes = []
  
  ##Unzip the newly built archive now
  unzip_archive(version)
    
  # Step 4: Upload javadocs to FTP
  prettyprint("Step 4: Uploading Javadocs", Levels.INFO)
  do_task(upload_javadocs, [base_dir, version], async_processes)
  prettyprint("Step 4: Complete", Levels.INFO)
  
  prettyprint("Step 5: Uploading to Sourceforge", Levels.INFO)
  do_task(upload_artifacts_to_sourceforge, [base_dir, version], async_processes)    
  prettyprint("Step 5: Complete", Levels.INFO)
  
  prettyprint("Step 6: Uploading to configuration XML schema", Levels.INFO)
  do_task(upload_schema, [base_dir, version], async_processes)    
  prettyprint("Step 6: Complete", Levels.INFO)
  
  ## Wait for processes to finish
  for p in async_processes:
    p.start()
  
  for p in async_processes:
    p.join()
  
  ## Clean up in git
  git.tag_for_release()
  if not settings['dry_run']:
    git.push_to_origin()
    git.cleanup()
  else:
    prettyprint("In dry-run mode.  Not pushing tag to remote origin and not removing temp release branch %s." % git.working_branch, Levels.DEBUG)
  
  prettyprint("\n\n\nDone!  Now all you need to do is the remaining post-release tasks as outlined in https://docspace.corp.redhat.com/docs/DOC-28594", Levels.INFO)

if __name__ == "__main__":
  release()
