/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package services.web;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.WriteResult;
import services.actuator.StartupCheck;
import services.ai.VertexAIClient;
import services.config.CloudConfig;
import services.domain.BooksService;
import services.domain.FirestoreService;
import services.utility.JsonUtility;
import services.utility.RequestValidationUtility;

/**
 * ImageProcessingController is a Spring Boot REST controller that listens for Cloud Storage events
 * and processes the uploaded images.
 *
 * Controller is responsible for:
 * 1. Receiving the Cloud Storage event
 * 2. Extracting the image metadata
 * 3. Extracting the text from the image
 * 4. Extracting the book details from the text
 * 5. Extracting the book summary from the database
 * 6. Calling the LLM to generate a report for the book including a book summary
 * and the up-to-date availability in the Bookstore
 * 7. Uses the BookStoreService to check the availability of the book, invoked by the LLM
 * using the function calling feature
 * 8. Saving the image metadata in Firestore
 */
@RestController
@RequestMapping("/images")
public class ImageProcessingController {
    private static final Logger logger = LoggerFactory.getLogger(ImageProcessingController.class);

    private final FirestoreService eventService;
    private BooksService booksService;

    @Value("${prompts.promptImage}")
    private String promptImage;

    public ImageProcessingController(FirestoreService eventService, BooksService booksService, VertexAIClient vertexAIClient) {
        this.eventService = eventService;
        this.booksService = booksService;
        this.vertexAIClient = vertexAIClient;
    }

    VertexAIClient vertexAIClient;

    @PostConstruct
    public void init() {
        logger.info("BookImagesApplication: ImageProcessingController Post Construct Initializer " + new SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(System.currentTimeMillis())));
        logger.info("BookImagesApplication: ImageProcessingController Post Construct - StartupCheck can be enabled");

        StartupCheck.up();
    }

    @GetMapping("start")
    String start(){
        logger.info("BookImagesApplication: ImageProcessingController - Executed start endpoint request " + new SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(System.currentTimeMillis())));
        return "ImageProcessingController started";
    }

    @RequestMapping(value = "", method = RequestMethod.POST)
    public ResponseEntity<String> receiveMessage(
            @RequestBody Map<String, Object> body, @RequestHeader Map<String, String> headers) throws IOException, InterruptedException, ExecutionException {
        String errorMsg = RequestValidationUtility.validateRequest(body,headers);
        if (!errorMsg.isBlank()) {
            return new ResponseEntity<>(errorMsg, HttpStatus.BAD_REQUEST);
        }

        String fileName = (String)body.get("name");
        String bucketName = (String)body.get("bucket");

        logger.info("New picture uploaded " + fileName);

        // multi-modal call to retrieve text from the uploaded image
        String response = vertexAIClient.promptOnImage(promptImage, bucketName, fileName);

        // parse the response and extract the data
        Map<String, Object> jsonMap = JsonUtility.parseJsonToMap(response);

        // get book details
        String title = (String) jsonMap.get("title");
        String author = (String) jsonMap.get("author");
        logger.info(String.format("Result: Author %s, Title %s", author, title));

        // retrieve the book summary from the database
        String summary = booksService.getBookSummary(title);
        logger.info("The summary of the book:"+ title+ " is: " + summary);

        // Function calling BookStoreService
        UserMessage userMessage = new UserMessage(
                String.format("Write a nice note including book author, book title and availability. Find out if the book with the title %s by author %s is available in the University bookstore.Please add also this book summary to the response, with the text available after the column, prefix it with My Book Summary:  %s",
                        title, author, summary));

        String bookStoreResponse = vertexAIClient.promptModelwithFunctionCalls(userMessage,
                new BookStoreService());

        // Saving result to Firestore
        if (bookStoreResponse != null) {
            ApiFuture<WriteResult> writeResult = eventService.storeBookInfo(fileName, title, author, summary, bookStoreResponse);
            logger.info("Picture metadata saved in Firestore at " + writeResult.get().getUpdateTime());
        }

        return new ResponseEntity<String>(HttpStatus.OK);
    }

    /**
     * BookStoreService is a function that checks the availability of a book in the bookstore.
     * Invoked by the LLM using the function calling feature.
     */
    static class BookStoreService {
        private static final Logger logger = LoggerFactory.getLogger(BookStoreService.class);
        @Tool("Find book availability in bookstore based on the book title and book author")
        BookStoreResponse getBookAvailability(@P("The title of the book") String title,
                                              @P("The author of the book") String author) {
            logger.info(String.format("Called getBookAvailability(%s, %s)", title, author));
            return new BookStoreResponse(title, author, "The book is available for purchase in the book store in paperback format.");
        }

        public record BookStoreRequest(
                @P("The title of the book") String title,
                @P("The author of the book") String author) {
        }
        public record BookStoreResponse(String title, String author, String availability) {}
    }
}