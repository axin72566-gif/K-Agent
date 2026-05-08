package com.axin.kagent.agentscope;

public final class GameModels {

    private GameModels() {}

    public record WerewolfKillTarget(String targetName, String reason) {
        public WerewolfKillTarget {
            if (targetName == null || targetName.isBlank()) {
                throw new IllegalArgumentException("targetName 不能为空");
            }
        }

        public static String formatHint() {
            return """
                仅回复 JSON：{"targetName":"<名字>","reason":"<理由>"}
                """;
        }
    }

    public record DiscussionOutput(boolean reachAgreement, int confidenceLevel, String keyEvidence) {
        public DiscussionOutput {
            if (confidenceLevel < 1 || confidenceLevel > 10) {
                throw new IllegalArgumentException("confidenceLevel 必须在 1 到 10 之间");
            }
        }

        public static String formatHint() {
            return """
                仅回复 JSON：
                {"reachAgreement":true|false,"confidenceLevel":<1-10>,"keyEvidence":"<证据>"}
                """;
        }
    }

    public record VoteOutput(String targetName, String reason) {
        public VoteOutput {
            if (targetName == null || targetName.isBlank()) {
                throw new IllegalArgumentException("targetName 不能为空");
            }
        }

        public static String formatHint() {
            return """
                仅回复 JSON：{"targetName":"<名字>","reason":"<理由>"}
                """;
        }
    }

    public record SeerVerifyOutput(String targetName) {
        public SeerVerifyOutput {
            if (targetName == null || targetName.isBlank()) {
                throw new IllegalArgumentException("targetName 不能为空");
            }
        }

        public static String formatHint() {
            return """
                仅回复 JSON：{"targetName":"<要查验的玩家名字>"}
                """;
        }
    }

    public record WitchActionOutput(boolean useAntidote, boolean usePoison, String targetName) {
        public static String formatHint() {
            return """
                仅回复 JSON：
                {"useAntidote":true|false,"usePoison":true|false,"targetName":"<名字或 null>"}
                """;
        }
    }
}
