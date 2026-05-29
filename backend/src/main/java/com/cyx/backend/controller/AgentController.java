package com.cyx.backend.controller;

import com.cyx.backend.dto.AgentChatRequest;
import com.cyx.backend.dto.AgentChatResponse;
import com.cyx.backend.dto.PendingAgentAction;
import com.cyx.backend.service.AgentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(@Valid @RequestBody AgentChatRequest request) {
        return agentService.chat(request);
    }

    @PostMapping("/confirm")
    public AgentChatResponse confirm(@RequestBody PendingAgentAction action) {
        return agentService.confirm(action);
    }
}
