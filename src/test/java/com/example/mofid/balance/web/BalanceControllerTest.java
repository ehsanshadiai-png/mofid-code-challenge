package com.example.mofid.balance.web;

import com.example.mofid.balance.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BalanceControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullFlowOverHttp() throws Exception {
        postJson("/api/accounts", """
                {"accountId": "A", "openingBalance": 1000}""").andExpect(status().isCreated());
        postJson("/api/accounts", """
                {"accountId": "B", "openingBalance": 500}""").andExpect(status().isCreated());

        postJson("/api/accounts/A/credit", """
                {"amount": 100, "transactionId": "TX-1"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1100));
        postJson("/api/accounts/A/credit", """
                {"amount": 100, "transactionId": "TX-1"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1100));

        postJson("/api/transfers", """
                {"sourceAccountId": "A", "destinationAccountId": "B", "amount": 300, "transactionId": "TX-2"}""")
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/accounts/B/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(800));
    }

    @Test
    void mapsFailuresToProblemDetails() throws Exception {
        postJson("/api/accounts", """
                {"accountId": "A", "openingBalance": 100}""").andExpect(status().isCreated());

        postJson("/api/accounts", """
                {"accountId": "A", "openingBalance": 100}""").andExpect(status().isConflict());
        postJson("/api/accounts/A/debit", """
                {"amount": 0, "transactionId": "TX-1"}""").andExpect(status().isBadRequest());
        postJson("/api/accounts/A/debit", """
                {"transactionId": "TX-1"}""").andExpect(status().isBadRequest());
        postJson("/api/accounts/missing/debit", """
                {"amount": 10, "transactionId": "TX-1"}""").andExpect(status().isNotFound());
        postJson("/api/accounts/A/debit", """
                {"amount": 500, "transactionId": "TX-1"}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
        postJson("/api/accounts/A/credit", """
                {"amount": 500, "transactionId": "TX-1"}""").andExpect(status().isConflict());
        postJson("/api/transfers", """
                {"sourceAccountId": "A", "destinationAccountId": "A", "amount": 1, "transactionId": "TX-2"}""")
                .andExpect(status().isBadRequest());
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
