package io.jettra.core.dl;

import java.util.Random;

public class BasicBrain implements AgentIntelligence {
    private Random random = new Random();

    @Override
    public String determineNextAction(String currentState, float energy, float socialNeed) {
        if (energy < 20) return "Resting";
        if (socialNeed < 30) return "Socializing";
        
        String[] actions = {"Exploring", "Studying", "Working", "Building"};
        return actions[random.nextInt(actions.length)];
    }

    @Override
    public String generateThought(String action) {
        return "I am currently " + action.toLowerCase() + "...";
    }

    @Override
    public void learnFromText(String text) {
        System.out.println("Brain learning from: " + (text.length() > 50 ? text.substring(0, 50) + "..." : text));
    }
}
