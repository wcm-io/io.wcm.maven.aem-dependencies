<img src="https://wcm.io/images/favicon-16@2x.png"/> wcm.io AEM Dependencies
======
[![Build](https://github.com/wcm-io/io.wcm.maven.aem-dependencies/workflows/Build/badge.svg?branch=develop)](https://github.com/wcm-io/io.wcm.maven.aem-dependencies/actions?query=workflow%3ABuild+branch%3Adevelop)
[![Maven Central](https://img.shields.io/maven-central/v/io.wcm.maven/io.wcm.maven.aem-dependencies)](https://repo1.maven.org/maven2/io/wcm/maven/io.wcm.maven.aem-dependencies/)

Maven dependencies the AEM 6.4/6.5

Documentation: https://wcm.io/tooling/maven/aem-dependencies.html<br/>
Issues: https://wcm-io.atlassian.net/browse/WTOOL<br/>
Wiki: https://wcm-io.atlassian.net/wiki/<br/>
Continuous Integration: https://github.com/wcm-io/io.wcm.maven.aem-dependencies/actions<br/>
Commercial support: https://wcm.io/commercial-support.html


## Usage

This POM defines Maven dependencies for AEM 6.4/6.5, including those that are not defined in the `uber-jar` API JAR provided by Adobe. Additionally, the POM includes Sling-internal dependencies required for [AEM Mocks](https://wcm.io/testing/aem-mock/) in exactly the versions included in the AEM version.

The version number of the artifact matches the AEM and service pack level version, plus a last part (4 digits) which is the revision number added by the wcm.io project, and is incremented if a fix or update of the POM needs to be published for the same AEM version.

To import the dependencies in your AEM project use:

```xml
<dependency>
  <groupId>io.wcm.maven</groupId>
  <artifactId>io.wcm.maven.aem-dependencies</artifactId>
  <version><!-- latest version, see above --></version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```
