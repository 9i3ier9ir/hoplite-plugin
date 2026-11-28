package com.example.hoplite;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class QuestManager {
    public static final class Quest {
        private final String id;
        private final String description;
        private final int goal;
        private final long reward;

        private Quest(String id, String description, int goal, long reward) {
            this.id = id;
            this.description = description;
            this.goal = goal;
            this.reward = reward;
        }

        public String getId() {
            return id;
        }

        public String getDescription() {
            return description;
        }

        public int getGoal() {
            return goal;
        }

        public long getReward() {
            return reward;
        }
    }

    public static final class Progress {
        private final int progress;
        private final boolean completed;
        private final boolean newlyCompleted;
        private final long coinBalance;

        public Progress(int progress, boolean completed, boolean newlyCompleted, long coinBalance) {
            this.progress = progress;
            this.completed = completed;
            this.newlyCompleted = newlyCompleted;
            this.coinBalance = coinBalance;
        }

        public int getProgress() {
            return progress;
        }

        public boolean isCompleted() {
            return completed;
        }

        public boolean isNewlyCompleted() {
            return newlyCompleted;
        }

        public long getCoinBalance() {
            return coinBalance;
        }
    }

    private static final List<Quest> DAILY_QUESTS = Collections.unmodifiableList(Arrays.asList(
            new Quest("win_match", "Win 1 match", 1, 50),
            new Quest("kill_5", "Get 5 kills", 5, 25),
            new Quest("play_3", "Play 3 matches", 3, 20),
            new Quest("kill_10", "Get 10 kills", 10, 50),
            new Quest("get_mace", "Pick up the event Mace", 1, 40)
    ));

    private QuestManager() {
    }

    public static List<Quest> getDailyQuests() {
        return DAILY_QUESTS;
    }

    public static Quest getQuest(String id) {
        for (Quest quest : DAILY_QUESTS) {
            if (quest.getId().equals(id)) return quest;
        }
        return null;
    }
}
