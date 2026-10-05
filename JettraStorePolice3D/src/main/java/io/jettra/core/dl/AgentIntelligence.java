package io.jettra.core.dl;

public interface AgentIntelligence {
    String determineNextAction(String currentState, float energy, float socialNeed);
    String generateThought(String action);
    void learnFromText(String text);
}
