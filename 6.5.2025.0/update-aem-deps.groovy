/*
 * #%L
 * wcm.io
 * %%
 * Copyright (C) 2020 wcm.io
 * %%
 * Licensed under the Apache License, Version 2.0 (the 'License');
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

/***********************************************************************
 *
 * PARAMETERS
 *
 ***********************************************************************/

LOCAL_AEM_URL = 'http://localhost:45026'
LOCAL_AEM_USER = 'admin'
LOCAL_AEM_PASSWORD = 'admin'

//----------------------------------------------------------------------

@GrabConfig(systemClassLoader=true)
@Grab('org.slf4j:slf4j-simple:2.0.12')
@Grab('jaxen:jaxen:1.1.6')
@GrabExclude('jdom:jdom')
@Grab('org.jdom:jdom2:2.0.6.1')

import org.jdom2.*
import org.jdom2.filter.*
import org.jdom2.input.*
import org.jdom2.xpath.*
import org.jdom2.output.*
import groovy.xml.XmlSlurper
import groovy.json.JsonSlurper
import groovy.grape.Grape
import org.slf4j.LoggerFactory
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.regex.Pattern

HINT_PATTERN = /\s*update-aem-deps:([^\s]*)\s*/

log = LoggerFactory.getLogger(this.class)
exitWithFailure = false

// get bundles version running in local AEM instance
log.info 'Reading bundle versions...'
def bundleData = [:]
readBundleData(bundleData)

// read process pom using JDOM to preserve formatting and whitespaces
POM_NS = Namespace.getNamespace('ns', 'http://maven.apache.org/POM/4.0.0')
Document doc = new SAXBuilder().build(new FileReader('pom.xml'))

// update some well-known properties based on specific bundle versions
log.info 'Update properties...'
pomUpdateProperties(doc, bundleData)

// update all dependencies matching the bundles in local POM
log.info 'Update dependencies...'
pomUpdateDependencies(doc, bundleData)

// validate all dependencies
log.info 'Validate dependencies...'
pomValidateDependencies(doc)

// write back pom content
new XMLOutputter().with {
  format = Format.getRawFormat()
  format.setLineSeparator(LineSeparator.NONE)
  def os = new File('pom.xml').newOutputStream()
  output(doc, os)
  os.close()
}

if (exitWithFailure) {
  System.exit(1)
}



// --- functions ---

// read URL from locale AEM instance
def readAemUrl(relativeUrl) {
  def url = LOCAL_AEM_URL + relativeUrl
  try {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .authenticator(new Authenticator() {   
            public PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(LOCAL_AEM_USER, LOCAL_AEM_PASSWORD.toCharArray())
            }
        })
        .version(HttpClient.Version.HTTP_1_1)
        .build()
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .build()
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() == 200) {
      if (relativeUrl.endsWith('.json')) {
        return new JsonSlurper().parseText(response.body())
      }
      else {
        return response.body()
      }
    }
    else {
      throw new RuntimeException("Call failed with HTTP ${response.statusCode()}")
    }
  }
  catch (Exception ex) {
    throw new RuntimeException("Unable to access ${url}", ex)
  }
}

// read versions of all maven artifacts from bundle list
def readBundleData(bundleData) {
  def bundleList = readAemUrl('/system/console/bundles.json')
  bundleList.data.each {
    def version = it.version
    // try to resolve the implementation version from bundle header as it may differ from the bundle version
    def bundleDetails = readAemUrl("/system/console/bundles/${it.id}.json")
    def manifestHeader = bundleDetails.data[0].props.findResult { it.key == 'Manifest Headers' ? it : null }
    if (manifestHeader) {
      def implementationVersion = manifestHeader.value.findResult { it =~ /^Implementation-Version: (.*)$/ ? (it =~ /^Implementation-Version: (.*)$/)[0][1] : null }
      if (implementationVersion) {
        version = implementationVersion
      }
    }
    bundleData[it.symbolicName] = new BundleData(version: version)

    // read exported package versions
    def exportedPackages = bundleDetails.data[0].props.findResult { it.key == 'Exported Packages' ? it : null }
    def packageVersions = [:]
    if (exportedPackages && exportedPackages.value) {
      exportedPackages.value.each {
        def exportedPackage = "${it}"
        def packageVersionPattern = /(.*),version=(.*)/
        def matcher = (exportedPackage =~ packageVersionPattern)
        if (matcher.matches()) {
          packageVersions[matcher.group(1)] = matcher.group(2)
        }
      }
    }
    bundleData[it.symbolicName].packageVersions = packageVersions

  }
}

// set pom version
def pomSetAemSdkVersion(doc, aemSdkVersion) {
  def versions = XPathFactory.instance().compile('/ns:project/ns:version', Filters.element(), null, POM_NS).evaluate(doc)
  for (def version in versions) {
    version.text = "${aemSdkVersion}.0000-SNAPSHOT"
  }
}

// update property in POM to match with a specific bundle version
def pomUpdateProperties(doc, bundleData) {
  def props = XPathFactory.instance().compile('/ns:project/ns:properties/*', Filters.element(), null, POM_NS).evaluate(doc)
  for (def prop in props) {
    // check if previous sibling is a comment not with a dependency hint
    def elementIndex = prop.parent.getContent().indexOf(prop)
    def previousSibling = null
    while (elementIndex > 0) {
      previousSibling = prop.parent.getContent().get(--elementIndex)
      if (previousSibling instanceof Element || previousSibling instanceof Comment) {
        break
      }
      else {
        previousSibling = null
      }
    }
    if (previousSibling instanceof Comment && previousSibling.text =~ HINT_PATTERN) {
      def hint = (previousSibling.text =~ HINT_PATTERN)[0][1]
      // check for dervied-from hint
      def derivedFrom = getDerivedFromHint(hint)
      if (derivedFrom) {
        def actualVersion = getBundleVersion(bundleData, derivedFrom.bundleName)
        if (derivedFrom.version != actualVersion) {
          if (actualVersion) {
            log.warn "property ${prop.name} is derived from ${derivedFrom.bundleName}:${derivedFrom.version}, but that bundle has currently version ${actualVersion}, check manually"
            exitWithFailure = true
          }
          else {
            log.warn "property ${prop.name} is derived from ${derivedFrom.bundleName}:${derivedFrom.version}, but that bundle is not present, check manually"
            exitWithFailure = true
          }
        }
        continue
      }
      def bundleName = getBundleNameFromHint(hint)
      if (bundleName) {
        def version = getBundleVersion(bundleData, bundleName)
        assert version != null : 'Version of bundle ' + bundleName + ' not found'
        prop.text = version
      }
    }
  }
}

// updates all dependencies to their latest versions
def pomUpdateDependencies(doc, bundleData) {
  def deps = XPathFactory.instance().compile('/ns:project/ns:dependencyManagement/ns:dependencies/ns:dependency', Filters.element(), null, POM_NS).evaluate(doc)
  for (def dep in deps) {
    def groupId = dep.getChild('groupId', POM_NS).text
    def artifactId = dep.getChild('artifactId', POM_NS).text
    def versionElement = dep.getChild('version', POM_NS)

    // check for update hint comment
    def hint = getDependencyHint(dep)
    if (hint == "ignore") {
      continue
    }

    // check for dervied-from hint
    def derivedFrom = getDerivedFromHint(hint)
    if (derivedFrom) {
      def actualVersion = getBundleVersion(bundleData, derivedFrom.bundleName)
      if (derivedFrom.version != actualVersion) {
        if (actualVersion) {
          log.warn "${groupId}:${artifactId} is derived from ${derivedFrom.bundleName}:${derivedFrom.version}, but that bundle has currently version ${actualVersion}, check manually"
          exitWithFailure = true
        }
        else {
          log.warn "${groupId}:${artifactId} is derived from ${derivedFrom.bundleName}:${derivedFrom.version}, but that bundle is not present, check manually"
          exitWithFailure = true
        }
      }
      continue
    }

    // check if the version references a maven property - skip this dependencies, properties are update separately
    def existingVersion = versionElement.text
    if (existingVersion =~ /\$\{.*\}/) {
      continue
    }

    // try to resolve latest version from bundle list
    def version = null
    def bundleName = getBundleNameFromHint(hint)
    if (bundleName) {
      version = getBundleVersion(bundleData, bundleName)
    }
    else {
      def bundlePackage = getBundlePackageFromHint(hint)
      if (bundlePackage) {
        version = bundleData[bundlePackage.bundleName].packageVersions[bundlePackage.packageName]
      }
      else {
        // check for bundle = artifactId
        version = getBundleVersion(bundleData, artifactId)
        if (!version) {
          // alternatively check combination for groupId and artifactId
          version = getBundleVersion(bundleData, "${groupId}.${artifactId}")
        }
      }
    }
    if (version) {
      versionElement.text = version
    }
    else {
      log.warn "No matching bundle: ${groupId}:${artifactId}"
      exitWithFailure = true
    }
  }
}

// validate all dependencies to make sure they can be resolved in public repositories
def pomValidateDependencies(doc) {
  def deps = XPathFactory.instance().compile('/ns:project/ns:dependencyManagement/ns:dependencies/ns:dependency', Filters.element(), null, POM_NS).evaluate(doc)
  for (def dep in deps) {
    def groupId = dep.getChild('groupId', POM_NS).text
    def artifactId = dep.getChild('artifactId', POM_NS).text
    def version = dep.getChild('version', POM_NS).text

    // if version is a property try to resolve it in POM
    def propertyNameMatcher = (version =~ /\$\{(.*)\}/)
    if (propertyNameMatcher) {
      def propertyName = propertyNameMatcher[0][1]
      def props = XPathFactory.instance().compile('/ns:project/ns:properties/ns:' + propertyName, Filters.element(), null, POM_NS).evaluate(doc)
      for (def prop in props) {
        version = prop.text
      }
    }

    // try to resolve dependency
    try {
      // this may fail with "Error grabbing grapes", see https://stackoverflow.com/a/51159346 and https://issues.apache.org/jira/browse/GROOVY-8655
      Grape.grab(group: groupId, module: artifactId, version: version)
    }
    catch (Exception ex) {
      log.error ex.message
      exitWithFailure = true
    }
  }
}

// check for update-aem-deps hint in comment
def getDependencyHint(dep) {
  return dep.getContent().findResult { (it instanceof Comment) && it.text =~ HINT_PATTERN ? (it.text =~ HINT_PATTERN)[0][1] : null }
}

def getBundleNameFromHint(hint) {
  def bundleNamePattern = /bundle=(.*)/
  def matcher = (hint =~ bundleNamePattern)
  if (matcher.matches()) {
    return matcher.group(1)
  }
  else {
    return null
  }
}

def getDerivedFromHint(hint) {
  def derivedFromPattern = /derived-from=(.*):(.*)/
  def matcher = (hint =~ derivedFromPattern)
  if (matcher.matches()) {
    return [ bundleName: matcher.group(1), version: matcher.group(2) ]
  }
  else {
    return null
  }
}

def getBundlePackageFromHint(hint) {
  def bundlePackagePattern = /bundle-package=(.*):(.*)/
  def matcher = (hint =~ bundlePackagePattern)
  if (matcher.matches()) {
    return [ bundleName: matcher.group(1), packageName: matcher.group(2) ]
  }
  else {
    return null
  }
}

def getBundleVersion(bundleData, bundleName) {
  return bundleData[bundleName]?.version
}

class BundleData {
  String version
  Map packageVersions
}
