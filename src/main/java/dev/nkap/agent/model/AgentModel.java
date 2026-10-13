package dev.nkap.agent.model;

import java.util.List;

/** The model behind the agent. One method: the conversation so far in, the next step out. */
public interface AgentModel {

    ModelResult next(List<Message> messages);
}
