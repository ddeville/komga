package org.gotson.komga.domain.service

import mu.KotlinLogging
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.model.Status
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.SerieRepository
import org.springframework.data.auditing.AuditingHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.system.measureTimeMillis

private val logger = KotlinLogging.logger {}

@Service
class LibraryManager(
    private val fileSystemScanner: FileSystemScanner,
    private val serieRepository: SerieRepository,
    private val bookRepository: BookRepository,
    private val bookManager: BookManager,
    private val auditingHandler: AuditingHandler
) {

  @Transactional
  fun scanRootFolder(library: Library) {
    logger.info { "Updating library: ${library.name}, root folder: ${library.root}" }
    measureTimeMillis {
      val series = fileSystemScanner.scanRootFolder(library.fileSystem.getPath(library.root))

      // delete series that don't exist anymore
      if (series.isEmpty()) {
        logger.info { "Scan returned no series, deleting all existing series" }
        serieRepository.deleteAll()
      } else {
        series.map { it.url }.let { urls ->
          serieRepository.findByUrlNotIn(urls).forEach {
            urls.forEach { logger.info { "Deleting serie not on disk anymore: $it" } }
            serieRepository.delete(it)
          }
        }
      }

      series.forEach { newSerie ->
        val existingSerie = serieRepository.findByUrl(newSerie.url)

        // if serie does not exist, save it
        if (existingSerie == null) {
          serieRepository.save(newSerie)
        } else {
          // if serie already exists, update it
          if (newSerie.fileLastModified != existingSerie.fileLastModified) {
            logger.info { "Serie changed on disk, updating: ${newSerie.url}" }
            existingSerie.name = newSerie.name

            var anyBookchanged = false
            // update list of books with existing entities if they exist
            existingSerie.books = newSerie.books.map { newBook ->
              val existingBook = bookRepository.findByUrl(newBook.url) ?: newBook

              if (newBook.fileLastModified != existingBook.fileLastModified) {
                logger.info { "Book changed on disk, update and reset metadata status: ${newBook.url}" }
                existingBook.fileLastModified = newBook.fileLastModified
                existingBook.name = newBook.name
                existingBook.metadata.reset()
                anyBookchanged = true
              }
              existingBook
            }.toMutableList()

            // propagate modification of any of the books to the serie, so that LastModifiedDate is updated
            if (anyBookchanged)
              auditingHandler.markModified(existingSerie)

            serieRepository.save(existingSerie)
          }
        }
      }
    }.also { logger.info { "Library update finished in $it ms" } }
  }

  @Transactional
  fun parseUnparsedBooks() {
    logger.info { "Parsing all books in status: unkown" }
    val booksToParse = bookRepository.findAllByMetadataStatus(Status.UNKNOWN)
    measureTimeMillis {
      booksToParse.forEach { bookManager.parseAndPersist(it) }
    }.also { logger.info { "Parsed ${booksToParse.size} books in $it ms" } }
  }
}