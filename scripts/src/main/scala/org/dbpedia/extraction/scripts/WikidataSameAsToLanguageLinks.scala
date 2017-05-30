package org.dbpedia.extraction.scripts

import java.io.File
import java.util.regex.Matcher

import org.dbpedia.extraction.config.provenance.DBpediaDatasets
import org.dbpedia.extraction.destinations.formatters.Formatter
import org.dbpedia.extraction.destinations.formatters.UriPolicy._
import org.dbpedia.extraction.destinations.{CompositeDestination, Destination, WriterDestination}
import org.dbpedia.extraction.ontology.RdfNamespace
import org.dbpedia.extraction.scripts.WikidataSameAsToLanguageLinks.{DBPEDIA_URI_PATTERN, error, sameAs}
import org.dbpedia.extraction.transform.Quad
import org.dbpedia.extraction.util.ConfigUtils._
import org.dbpedia.extraction.util.IOUtils._
import org.dbpedia.extraction.util.RichFile.wrapFile
import org.dbpedia.extraction.util._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Generates language links from the Wikidata sameAs dataset as created by the
 * [[org.dbpedia.extraction.mappings.WikidataSameAsExtractor]]. This code assumes the subjects to be
 * ordered, in particular, it assumes that there is *exactly* one continuous block for each subject.
 *
 * @author Daniel Fleischhacker (daniel@informatik.uni-mannheim.de)
 */
object WikidataSameAsToLanguageLinks {
  private val sameAs = RdfNamespace.OWL.append("sameAs")
  private val DBPEDIA_URI_PATTERN = "^http://([a-z-]+.)?dbpedia.org/resource/.*$".r.pattern

  def main(args: Array[String]) {
    require(args != null && args.length == 1 && args(0).nonEmpty, "missing required argument: config file name")

    val config = new Config(args(0))

    val baseDir = config.dumpDir
    if (!baseDir.exists) {
      throw error("dir " + baseDir + " does not exist")
    }

    val inputFinder = new Finder[File](baseDir, Language.Wikidata, "wiki")
    val date = inputFinder.dates().last

    val suffix = config.inputSuffix match{
      case Some(x) => x
      case None => throw new IllegalArgumentException("Please provide a 'suffix' attribute in your properties configuration")
    }

    val output = config.outputDataset match{
      case Some (l) => l
      case None => throw new IllegalArgumentException("Please provide an 'output' attribute for the output dataset file in the .properties configuration.")
    }

    val language = config.languages

    val policies = config.policies
    val formats = config.formats

    val input = config.inputDatasets.headOption.getOrElse(throw new IllegalArgumentException("Please provide an 'input' attribute for the wikidata input file in the .properties configuration."))

    // find the input wikidata file
    val wikiDataFile: RichFile = inputFinder.file(date, input + suffix).get

    val processor = new WikidataSameAsToLanguageLinks(baseDir, wikiDataFile, output, language, formats)
    processor.processLinks()
  }

  private def error(message: String, cause: Throwable = null): IllegalArgumentException = {
    new IllegalArgumentException(message, cause)
  }
}


class WikidataSameAsToLanguageLinks(val baseDir: File, val wikiDataFile: FileLike[_],
                                    val output: String, val languages: Array[Language],
                                    val formats: collection.Map[String, Formatter]) {
  private val relevantLanguages: Set[String] = languages.map(_.wikiCode).toSet
  private val destinations = setupDestinations()


  // all entities assigned to the current wikidata entity by means of sameAs
  private var currentSameEntities = new mutable.HashMap[String, EntityContext]()

  private val workers: Workers[(String, String, Map[String, EntityContext])] = setupWorkers()

  /**
   * Starts the generation of the inter-language links from sameAs information contained in the given
   * wikiDataFile.
   */
  def processLinks(): Unit = {
    // open all writers for all relevant languages
    destinations.foreach(_._2.open())

    // start worker threads
    workers.start()

    // stores the currently processed wikidata entity to recognize when the current block is fully read
    var currentWikidataEntity: Option[String] = None
    new QuadMapper().readQuads(Language.Wikidata, wikiDataFile) { quad =>
      val currentSubject = quad.subject

      currentWikidataEntity match {
        case None =>
          // we have not yet read any data, start from scratch
          currentWikidataEntity = Some(currentSubject)

          matchAndSore(quad)
        case Some(subj) if subj == currentSubject =>
          // still at the current subject, collect object
          matchAndSore(quad)
        case Some(subj) =>
          // we are at the next subject, write out already collected links
          writeQuads(subj, currentSameEntities.toMap)

          // now we can set the variables wrt the current line
          currentWikidataEntity = Some(currentSubject)
          currentSameEntities = new mutable.HashMap[String, EntityContext]()

          matchAndSore(quad)

      }
    }

    if (currentWikidataEntity.isDefined) {
      writeQuads(currentWikidataEntity.get, currentSameEntities.toMap)
    }
    // wait for all workers to finish writing
    workers.stop()
    // close all destinations
    destinations.foreach(_._2.close())
  }

  def matchAndSore(quad: Quad): Unit = {
    val matcher: Matcher = DBPEDIA_URI_PATTERN.matcher(quad.value)
    if (!matcher.matches()) {
      error("Non-DBpedia URI found in sameAs statement of Wikidata sameAs links!")
    }
    else {
      val lang = matcher.group(1)
      if (lang == null) {
        // URI starts with http://dbpedia.org..
        currentSameEntities("en") = new EntityContext(quad.value, quad.context)
      }
      else {
        // non-English URI ==> store entity and context in list
        if (relevantLanguages.contains(lang.replace(".", ""))) {
          currentSameEntities(lang.replace(".", "")) = new EntityContext(quad.value, quad.context)
        }
      }
    }
  }

  /**
   * Submits the jobs for writing quad data to the initialized workers.
   *
   * @param wikiDataEntity wikidata entity for which quads have to be written
   * @param sameEntities entities assigned to be the same as the given wikidata entity
   */
  private def writeQuads(wikiDataEntity: String, sameEntities: Map[String, EntityContext]) : Unit = {
    relevantLanguages.foreach { language =>
      workers.process((language, wikiDataEntity, sameEntities))
    }
  }

  /**
   * Sets up the destinations for the relevant languages in all configured formats but does not yet open
   * the destinations.
   */
  private def setupDestinations(): Map[String, Destination] = {
    var destinations = Map[String, Destination]()
    for (currentLanguage <- languages) {
      val outputFinder = new Finder[File](baseDir, currentLanguage, "wiki")
      val outputDate = outputFinder.dates().last
      val formatDestinations = new ArrayBuffer[Destination]()
      for ((suffix, format) <- formats) {
        val file = outputFinder.file(outputDate, output + '.' + suffix).get
        formatDestinations += new WriterDestination(() => writer(file), format)
      }
      destinations += currentLanguage.wikiCode -> new CompositeDestination(formatDestinations.toSeq: _*)
    }
    destinations.toMap
  }

  /**
   * Sets up the workers for writing quads into files. Workers are not yet started after calling this method.
   */
  private def setupWorkers(): Workers[(String, String, Map[String, EntityContext])] = {
    SimpleWorkers(1.5, 1.5) { job: (String, String, Map[String, EntityContext]) =>
      val language = job._1
      val wikiDataEntity = job._2
      val sameEntities = job._3
      sameEntities.get(language) match {
        case Some(currentEntity) =>
          // generate quads for the current language and prepend the sameAs statement quad to the
          // wikidata entity
          var quads = List[Quad]()
          quads :::= sameEntities.filterKeys(_ != language).toList.sortBy(_._1).map { case (language, context) =>
            new Quad(language, null, currentEntity.entityUri, sameAs, context.entityUri, context.context, null: String)
          }
          quads ::= new Quad(language, null, currentEntity.entityUri, sameAs, wikiDataEntity, currentEntity.context,
            null: String)
          quads ::= new Quad(language, null, currentEntity.entityUri, sameAs, getWikidataUri(wikiDataEntity),
            currentEntity.context, null: String)
          destinations(language).write(quads)
        case _ => // do not write anything when there is no entity in the current language
      }
    }
  }

  /**
   * Builds the wikidata.org URI for the given wikidata.dbpedia.org URI
   */
  def getWikidataUri(entity: String) : String = {
    val wikidataName = entity.split("/").last
    s"http://www.wikidata.org/entity/$wikidataName"
  }

  /**
   * Represents the combination of an entity URI which is assigned to some wikidata entity by means
   * of owl:sameAs and the context in which this statement is made.
 *
   * @param entityUri URI of the entity
   * @param context context in which this entity's sameAs statement is given
   */
  private class EntityContext(val entityUri: String, val context: String)
}