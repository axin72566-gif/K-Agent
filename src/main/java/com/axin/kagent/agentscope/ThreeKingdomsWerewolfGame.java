package com.axin.kagent.agentscope;

import com.axin.kagent.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.pipeline.MsgHub;
import io.agentscope.core.pipeline.Pipelines;

import java.util.*;

public class ThreeKingdomsWerewolfGame {

    private static final int MAX_WEREWOLF_DISCUSSION = 2;
    private static final int MAX_DAY_DISCUSSION = 2;
    private static final List<String> CHARACTERS =
        List.of("孙权", "周瑜", "曹操", "张飞", "司马懿", "赵云");
    private static final List<String> ROLES =
        List.of("狼人", "狼人", "预言家", "女巫", "村民", "村民");

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final Random random;

    private final List<Player> players = new ArrayList<>();
    private final List<Player> werewolves = new ArrayList<>();
    private final List<Player> villagers = new ArrayList<>();
    private final List<Player> alivePlayers = new ArrayList<>();
    private Player seer;
    private Player witch;
    private int roundNum;

    private WerewolfAgent moderator;

    public ThreeKingdomsWerewolfGame(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.objectMapper = new ObjectMapper();
        this.random = new Random();
    }

    // ═══════════════════════════════════════════════════════════════
    // 玩家记录
    // ═══════════════════════════════════════════════════════════════

    record Player(String name, String character, String role, WerewolfAgent agent) {}

    // ═══════════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════════

    public void init() {
        players.clear();
        werewolves.clear();
        villagers.clear();
        alivePlayers.clear();
        roundNum = 0;

        moderator = new WerewolfAgent("游戏主持", """
            你是三国狼人杀游戏的主持人。
            引导游戏进程，宣布事件，执行规则。
            使用符合三国时代的正式叙事风格。
            """, llmClient);

        for (int i = 0; i < CHARACTERS.size(); i++) {
            String character = CHARACTERS.get(i);
            String role = ROLES.get(i);
            WerewolfAgent agent = new WerewolfAgent(character,
                buildRolePrompt(role, character), llmClient);
            Player p = new Player(character, character, role, agent);
            players.add(p);
            alivePlayers.add(p);

            switch (role) {
                case "狼人" -> werewolves.add(p);
                case "预言家" -> seer = p;
                case "女巫" -> witch = p;
                default -> villagers.add(p);
            }
        }

        System.out.println("🎮 欢迎来到三国狼人杀！\n");
        System.out.println("=== 游戏初始化 ===");
        for (Player p : players) {
            System.out.println("游戏主持：📢 【" + p.character
                + "】 你是" + describeRole(p.role)
                + "，你的角色是 " + p.character + "。");
        }
        System.out.println();
        System.out.println("游戏主持：📢 三国狼人杀游戏开始！"
            + "参与者：" + String.join(", ", CHARACTERS));
        System.out.println("✅ 游戏设置完成，共 " + players.size()
            + " 名玩家\n");
    }

    // ═══════════════════════════════════════════════════════════════
    // 游戏循环
    // ═══════════════════════════════════════════════════════════════

    public void run() {
        init();
        while (true) {
            roundNum++;
            System.out.println("=== 第 " + roundNum + " 轮 ===");
            nightPhase();
            if (checkWinCondition()) break;
            dayPhase();
            if (checkWinCondition()) break;
        }
        System.out.println("\n🏆 游戏结束！");
    }

    // ═══════════════════════════════════════════════════════════════
    // 夜晚阶段
    // ═══════════════════════════════════════════════════════════════

    private void nightPhase() {
        System.out.println("🌙 第 " + roundNum
            + " 夜降临，所有人请闭眼...\n");
        Player killed = werewolfPhase();
        seerPhase();
        boolean saved = witchPhase(killed);

        if (saved && killed != null) {
            System.out.println("游戏主持：📢 昨晚平安无事，没有人死亡。\n");
        } else if (killed != null) {
            alivePlayers.remove(killed);
            System.out.println("游戏主持：📢 昨晚 " + killed.name + " 被杀死了。\n");
        }
    }

    private Player werewolfPhase() {
        if (werewolves.isEmpty()) return null;

        System.out.println("【狼人阶段】");
        List<WerewolfAgent> wolves = werewolves.stream().map(Player::agent).toList();
        String aliveStr = alivePlayerNames();

        Msg announce = buildSystemMsg("狼人们，讨论今晚的击杀目标。"
            + "存活者：" + aliveStr);

        MsgHub hub = MsgHub.builder()
            .participants(new ArrayList<>(wolves))
            .enableAutoBroadcast(true)
            .announcement(announce)
            .build();

        hub.enter().block();

        // 讨论轮次
        for (int r = 0; r < MAX_WEREWOLF_DISCUSSION; r++) {
            for (WerewolfAgent wolf : wolves) {
                try {
                    Msg response = wolf.call(List.of()).block();
                    if (response != null) {
                        hub.broadcast(response).block();
                    }
                } catch (Exception e) {
                    System.out.println("⚠️ " + wolf.getName()
                        + " 讨论时出错：" + e.getMessage());
                }
            }
        }

        // 投票
        hub.setAutoBroadcast(false);
        Msg voteMsg = buildSystemMsg("选择击杀目标。\n"
            + GameModels.WerewolfKillTarget.formatHint());

        hub.exit().block();

        List<Msg> votes = Pipelines.fanout(new ArrayList<>(wolves), voteMsg).block();
        System.out.println("游戏主持：📢 请选择击杀目标");

        String chosen = tallyVotes(votes, alivePlayers);
        if (chosen == null) {
            chosen = alivePlayers.get(random.nextInt(alivePlayers.size())).name;
        }
        System.out.println("游戏主持：📢 狼人选择杀死 " + chosen + "\n");
        return findPlayer(chosen);
    }

    private void seerPhase() {
        if (seer == null || !alivePlayers.contains(seer)) return;

        System.out.println("【预言家阶段】");
        String others = alivePlayers.stream()
            .filter(p -> p != seer).map(Player::name)
            .reduce((a, b) -> a + ", " + b).orElse("");

        Msg msg = buildSystemMsg("选择一位玩家进行查验。可选：" + others
            + "\n" + GameModels.SeerVerifyOutput.formatHint());

        Msg response = safeCall(seer.agent, msg);
        System.out.println(response.getName() + "：" + response.getTextContent());

        String target = parseField(response.getTextContent(), "targetName");
        if (target == null) {
            target = others.split(",")[0].strip();
        }

        Player t = findPlayer(target);
        if (t != null) {
            boolean isWolf = werewolves.contains(t);
            System.out.println("游戏主持：📢 查验结果：" + target
                + (isWolf ? " 是狼人" : " 是好人") + "\n");
        }
    }

    private boolean witchPhase(Player killed) {
        if (witch == null || !alivePlayers.contains(witch)) return false;

        System.out.println("【女巫阶段】");
        String killedName = killed != null ? killed.name : "无";
        String aliveStr = alivePlayerNames();

        System.out.println("游戏主持：📢 今晚 " + killedName + " 被袭击");

        Msg msg = buildSystemMsg("今晚 " + killedName + " 被袭击。存活者："
            + aliveStr + "\n" + GameModels.WitchActionOutput.formatHint());

        Msg response = safeCall(witch.agent, msg);
        System.out.println(response.getName() + "：" + response.getTextContent());

        String content = response.getTextContent();
        boolean useAntidote = parseBool(content, "useAntidote");
        boolean usePoison = parseBool(content, "usePoison");

        if (useAntidote && killed != null) {
            System.out.println("游戏主持：📢 女巫救了 " + killed.name + "\n");
            return true;
        }
        if (usePoison) {
            String target = parseField(content, "targetName");
            if (target != null) {
                Player t = findPlayer(target);
                if (t != null && alivePlayers.contains(t) && t != witch) {
                    alivePlayers.remove(t);
                    System.out.println("游戏主持：📢 " + t.name
                        + " 被毒杀了\n");
                }
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // 白天阶段
    // ═══════════════════════════════════════════════════════════════

    private void dayPhase() {
        System.out.println("【白天讨论阶段】");
        String aliveStr = alivePlayerNames();
        System.out.println("游戏主持：📢 ☀️ 第 " + roundNum + " 天到来...");
        System.out.println("游戏主持：📢 自由讨论。存活者：" + aliveStr + "\n");

        for (int r = 0; r < MAX_DAY_DISCUSSION; r++) {
            for (Player p : new ArrayList<>(alivePlayers)) {
                Msg discMsg = buildSystemMsg("第 " + roundNum + " 天第 "
                    + (r + 1) + " 轮讨论。存活者：" + aliveStr + "。请发表你的看法。");
                Msg resp = safeCall(p.agent, discMsg);
                System.out.println(resp.getName() + "：" + resp.getTextContent());
            }
        }

        // 投票
        System.out.println("\n【投票阶段】");
        Msg voteMsg = buildSystemMsg("投票淘汰。存活者：" + aliveStr
            + "\n" + GameModels.VoteOutput.formatHint());

        List<WerewolfAgent> aliveAgents = alivePlayers.stream()
            .map(Player::agent).toList();
        List<Msg> votes = Pipelines.fanout(new ArrayList<>(aliveAgents), voteMsg).block();

        System.out.println("游戏主持：📢 请投票淘汰");
        String eliminated = tallyVotes(votes, alivePlayers);
        if (eliminated != null) {
            Player ep = findPlayer(eliminated);
            if (ep != null) {
                alivePlayers.remove(ep);
                System.out.println("游戏主持：📢 " + eliminated
                    + " 被淘汰。身份：" + describeRole(ep.role) + "。\n");
                return;
            }
        }
        System.out.println("游戏主持：📢 无人被淘汰。\n");
    }

    // ═══════════════════════════════════════════════════════════════
    // 胜负判定
    // ═══════════════════════════════════════════════════════════════

    boolean checkWinCondition() {
        long aliveWolves = alivePlayers.stream().filter(werewolves::contains).count();
        long aliveGood = alivePlayers.size() - aliveWolves;
        if (aliveWolves == 0) {
            System.out.println("\n🎉 好人阵营获胜！所有狼人已被消灭！");
            return true;
        }
        if (aliveWolves >= aliveGood) {
            System.out.println("\n🐺 狼人阵营获胜！狼人占据了主导！");
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private String alivePlayerNames() {
        return alivePlayers.stream().map(Player::name)
            .reduce((a, b) -> a + ", " + b).orElse("");
    }

    private Msg buildSystemMsg(String content) {
        return Msg.builder().name("游戏主持")
            .role(MsgRole.SYSTEM).textContent(content).build();
    }

    private Player findPlayer(String name) {
        return players.stream().filter(p -> p.name.equals(name)).findFirst().orElse(null);
    }

    private String describeRole(String role) {
        return switch (role) {
            case "狼人" -> "狼人";
            case "预言家" -> "预言家";
            case "女巫" -> "女巫";
            default -> "村民";
        };
    }

    private Msg safeCall(WerewolfAgent agent, Msg msg) {
        try {
            agent.observe(msg).block();
            Msg response = agent.call(List.of()).block();
            return response != null ? response : fallbackMsg(agent.getName());
        } catch (Exception e) {
            System.out.println("⚠️ " + agent.getName() + " 出错：" + e.getMessage());
            return fallbackMsg(agent.getName());
        }
    }

    private Msg fallbackMsg(String name) {
        return Msg.builder().name(name).role(MsgRole.ASSISTANT)
            .textContent("无法回应。").build();
    }

    // ═══════════════════════════════════════════════════════════════
    // 投票统计
    // ═══════════════════════════════════════════════════════════════

    String tallyVotes(List<Msg> responses, List<Player> candidates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (responses == null) return null;
        for (Msg resp : responses) {
            if (resp == null) continue;
            System.out.println(resp.getName() + "：" + resp.getTextContent());
            String target = parseField(resp.getTextContent(), "targetName");
            if (target != null) counts.merge(target, 1, Integer::sum);
        }
        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(null);
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON 解析
    // ═══════════════════════════════════════════════════════════════

    String parseField(String content, String fieldName) {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node.has(fieldName) && !node.get(fieldName).isNull()) {
                return node.get(fieldName).asText();
            }
        } catch (Exception ignored) {}
        // 回退：扫描已知的玩家名字
        for (Player p : players) {
            if (content.contains(p.name)) return p.name();
        }
        return null;
    }

    boolean parseBool(String content, String fieldName) {
        try {
            JsonNode node = objectMapper.readTree(content);
            return node.has(fieldName) && node.get(fieldName).asBoolean();
        } catch (Exception ignored) {}
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // 提示词构建
    // ═══════════════════════════════════════════════════════════════

    private String buildRolePrompt(String role, String character) {
        String base = """
            你是 %s，在三国狼人杀游戏中扮演 %s。

            你身处一个以三国时代为背景的狼人杀游戏。
            说话和行事应符合 %s 的历史人物性格。

            重要规则：
            1. 仅通过对话和推理参与游戏。
            2. 不要调用任何外部工具或函数。
            3. 严格遵守请求的输出格式。
            4. 始终保持在角色设定中。

            游戏规则：
            - 6 名玩家：2 狼人、1 预言家、1 女巫、2 村民。
            - 狼人阵营在人数等于或超过好人阵营时获胜。
            - 好人阵营在所有狼人被淘汰时获胜。
            """.formatted(character, role, character);

        String roleSpecific = switch (role) {
            case "狼人" -> """
                你是狼人。消灭所有好人。
                夜晚与狼人同伴协商击杀目标。
                白天隐藏身份，误导他人。
                """;
            case "预言家" -> """
                你是预言家。每晚查验一名玩家。
                巧妙地利用这些信息引导村民。
                """;
            case "女巫" -> """
                你是女巫，拥有解药和毒药（各限一次）。
                根据情况合理使用药水。
                """;
            default -> """
                你是村民。通过观察和逻辑推理识别狼人。
                """;
        };
        return base + "\n\n" + roleSpecific;
    }

    // ═══════════════════════════════════════════════════════════════
    // 测试访问器
    // ═══════════════════════════════════════════════════════════════

    List<Player> getPlayers() { return new ArrayList<>(players); }
    List<Player> getWerewolves() { return new ArrayList<>(werewolves); }
    List<Player> getVillagers() { return new ArrayList<>(villagers); }
    List<Player> getAlivePlayers() { return alivePlayers; }
    Player getSeer() { return seer; }
    Player getWitch() { return witch; }
    int getRoundNum() { return roundNum; }
}
