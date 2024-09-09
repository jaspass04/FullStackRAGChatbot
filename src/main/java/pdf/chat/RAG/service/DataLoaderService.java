package pdf.chat.RAG.service;

import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.MongoDBAtlasVectorStore;
import org.bson.Document;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import static org.springframework.util.ResourceUtils.getFile;

@Service
@Slf4j
public class DataLoaderService {
    private final String collection = System.getenv("COLLECTION_NAME");

    public DataLoaderService(MongoDBAtlasVectorStore vectorStore, MongoTemplate mongoTemplate) {
        this.vectorStore = vectorStore;
        this.mongoTemplate = mongoTemplate;
    }
    public MongoDBAtlasVectorStore vectorStore;

    private MongoTemplate mongoTemplate;
    /**
     * Loads PDFs from the default classpath folder.
     * This method is called when no specific file URL is provided.
     * This is happening in two scenarios :
     * 1. During the init(), where the PDFs from docs folder are supposed to be loaded.
     * 2. During the load() call with no url specified.
     *
     * @throws MalformedURLException if the default folder path is incorrect or not accessible.
     */
    public void load() throws MalformedURLException {
        log.info("DataLoaderService::load - Loading PDFs from default classpath.");
        load("");
    }

    /**
     * Loads PDFs from either a specified file URL or from the default documents folder.
     * Processes each PDF, converts it to text, splits the text, and stores it in the vector store.
     * This method is called whether from ChatBotService::load() when an url is supposed to be given,
     * or from DataLoaderService::load() where the empty string ("") is given.
     *
     * @param file Optional URL of the PDF file to load. If empty, loads from the default folder.
     * @throws MalformedURLException if the provided file URL is malformed or inaccessible.
     */
    public void load(String file) throws MalformedURLException {
    Resource[] resources = file.isEmpty()
            ? folderLoader()
            : new Resource[]{new UrlResource(file)};

        for (Resource resource : resources) {
            log.debug("DataLoaderService::load - Processing PDF resource: {}", resource.getFilename());

            var config = PdfDocumentReaderConfig.builder()
                    .withPageExtractedTextFormatter(new ExtractedTextFormatter.Builder().build())
                    .withPagesPerDocument(1)
                    .build();

            var textSplitter = new TokenTextSplitter();
            try {
                var paragraphPdfDocumentReader = new ParagraphPdfDocumentReader(resource, config);
                vectorStore.accept(textSplitter.apply(paragraphPdfDocumentReader.get()));
                log.info("DataLoaderService::load - Successfully used ParagraphPdfDocumentReader");
            }catch(Exception e) {
                var pagePdfDocumentReader = new PagePdfDocumentReader(resource, config);
                vectorStore.accept(textSplitter.apply(pagePdfDocumentReader.get()));
                log.info("DataLoaderService::load - Exception happened while trying to use ParagraphPdfDocumentReader -- switched to PagePdfDocumentReader");
            }
            log.info("DataLoaderService::load - Successfully processed and stored resource: {}", resource.getFilename());

            if (file.isEmpty()) {
                deleteFile(resource);
            }
        }
    }

    /**
     * Loads all PDF files from the default folder located at 'src/main/resources/docs'.
     * This method is used when no specific file URL is provided and, basically, creates an array with
     * all Resources that are supposed to load in the database.
     *
     * @return An array of Resource objects representing the PDF files found in the folder.
     */
    private Resource[] folderLoader() {
        log.info("DataLoaderService::folderLoader - Loading all PDF files from folder: {}", "src/main/resources/docs");

        File folder = new File("src/main/resources/docs");
        File[] pdfFiles = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
        assert pdfFiles != null;
        Resource[] resources = new Resource[pdfFiles.length];

        for (int i = 0; i < pdfFiles.length; i++) {
            resources[i] = new FileSystemResource(pdfFiles[i]);
            log.debug("DataLoaderService::folderLoader - Found PDF file: {}", pdfFiles[i].getName());
        }
        return resources;
    }

    /**
     * Deletes the specified file after processing it.
     * This method is used to clean up files that were loaded from the default folder.
     * This method is only called from DataLoaderService::load() when PDFs are loaded from
     * docs folder.
     *
     * @param resource The resource representing the file to be deleted.
     */
    private void deleteFile(Resource resource) {
        try {
            File myFile = getFile(resource.getURI());
            boolean deleted = myFile.delete();
            if(!deleted) {
                throw new IOException("Failed to delete file: " + myFile.getCanonicalPath());
            }

            log.info("DataLoaderService::deleteFile - Successfully deleted file: {}", myFile.getCanonicalPath());
        } catch (IOException e) {
            log.error("DataLoaderService::deleteFile - Can not delete file that does not exist");
        }
    }

    /**
     * Clears all PDF documents from the specified collection in the MongoDB database.
     * This method removes all documents from the collection and should be used with caution.
     */
    public void clear() {
        log.info("DataLoaderService::clear - Clearing all documents from collection: {}", collection);
        mongoTemplate.getCollection(collection).deleteMany(new Document());
        log.info("DataLoaderService::clear - All documents cleared from collection: {}", collection);
    }
}
