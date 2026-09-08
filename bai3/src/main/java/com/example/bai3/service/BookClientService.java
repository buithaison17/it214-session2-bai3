package com.example.bai3.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
public class BookClientService {

    private final RestTemplate restTemplate;

    public String getBookTitle(Long bookId) {

        String url =
                "http://book-service/api/books/" + bookId;

        try {

            return restTemplate.getForObject(
                    url,
                    String.class
            );

        } catch (RestClientException e) {

            System.err.println(
                    "[BorrowingService] Cannot call book-service: "
                            + e.getMessage()
            );

            return "Book service is currently unavailable";
        }
    }
}