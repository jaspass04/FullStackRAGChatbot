package pdf.chat.RAG.service;

import java.net.MalformedURLException;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.*;
import reactor.core.publisher.Flux;

@Service
@Slf4j
public class ChatBotService {
    private final String collection = System.getenv("COLLECTION_NAME");

    @Autowired
    private ChatModel chatClient;

    @Autowired
    private DataRetrievalService dataRetrievalService;

    @Autowired
    private DataLoaderService dataLoaderService;

    @Autowired
    private MongoTemplate mongoTemplate;

    private final String PROMPT_BLUEPRINT = """
        You're assisting with questions about a Company's Confluence / Wiki.
       \s
        Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
        If unsure, simply state that you don't know.
       \s
        This is the question you have to answer based only on the information from DOCUMENTS sections:
        {query}
       \s
        DOCUMENTS:
        {context}
        \s
    """;

    /**
     * Processes a chat request with the given query and generates a response using the chat model.
     *
     * @param query The query to be processed by the chat service.
     * @return The response generated by the chat model based on the query and context.
     */
    public String chat(String query) {
        log.info("ChatBotService::chat - Received chat request with query: {}", query);
        return chatClient.call(createPrompt(query, dataRetrievalService.searchData(query)));
    }

    /**
     * Streams chat responses based on the given query using the chat model.
     *
     * @param query The query to be processed by the chat service for streaming responses.
     * @return A Flux of chat responses as a stream.
     */
    public Flux<String> chatStream(String query) {
        log.info("ChatBotService::chatStream - Received chat stream request with query: {}", query);
        return chatClient.stream(createPrompt(query, dataRetrievalService.searchData(query)));
    }

    /**
     * Creates a prompt for the chat model based on the provided query and context.
     *
     * @param query The query to be included in the prompt.
     * @param context The context or documents to be included in the prompt.
     * @return The rendered prompt string.
     */
    private String createPrompt(String query, List<Document> context) {
        log.debug("ChatBotService::createPrompt - Creating prompt with query: {} and context: {}", query, context);

        PromptTemplate promptTemplate = new PromptTemplate(PROMPT_BLUEPRINT);
        promptTemplate.add("query", query);
        promptTemplate.add("context", context);
        String renderedPrompt = promptTemplate.render();

        log.debug("ChatBotService::createPrompt - Rendered prompt: {}", renderedPrompt);
        return renderedPrompt;
    }

    /**
     * Loads documents into the database if none are present, called only from RAGController::init method.
     *
     * @throws MalformedURLException If there is an issue with the URL used to load documents (though none should be provided during init).
     * @dev TODO: Implement logic to check which files are already inserted into the database and which are not.
     */
    public void load() throws MalformedURLException {
        if (mongoTemplate.getCollection(collection).countDocuments() == 0) {
            log.info("ChatBotService::load - Loading documents from env variable set path folder location.");
            dataLoaderService.load();
        } else {
            log.info("ChatBotService::load - There are already files into the database.");
        }
    }

    /**
     * Loads documents from a specified file(meaning given an HTTP(s) url for a PDF) or the default location which is the docs' folder.
     *
     * @param file The optional URL of the file to load. If empty, loads from the default location.
     * @throws MalformedURLException If the provided file URL is malformed.
     */
    public void load(String file) throws MalformedURLException {
        // Empty string meaning "" given, so it will just load whatever is in docs' folder.
        // Non-empty string meaning an url given, so it will load that PDF.
        if (file.isEmpty()) {
            log.info("ChatBotService::load - Loading documents from whatever is in docs' folder.");
            dataLoaderService.load();
        } else {
            log.info("ChatBotService::load - Loading documents from specified file: {}", file);
            dataLoaderService.load(file);
        }
    }

    /**
     * Clears all documents from the database.
     * This action will remove all PDFs stored in the collection.
     * Please, use this with caution.
     */
    public void clear() {
        log.info("ChatBotService::clear - Clearing all documents from the collection.");
        dataLoaderService.clear();
    }
}
