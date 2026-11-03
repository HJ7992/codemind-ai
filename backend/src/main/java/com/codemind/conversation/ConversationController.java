package com.codemind.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    public ResponseEntity<ConversationDtos.Response> create(
            @RequestBody ConversationDtos.CreateConversationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(conversationService.create(request.projectId(), request.title()));
    }

    @GetMapping("/{conversationId}")
    public ConversationDtos.ConversationView get(@PathVariable String conversationId) {
        return conversationService.get(conversationId);
    }

    @PostMapping("/{conversationId}/questions")
    public ConversationDtos.AskResponse ask(
            @PathVariable String conversationId,
            @RequestBody ConversationDtos.QuestionRequest request) {
        return conversationService.ask(conversationId, request.question());
    }
}
