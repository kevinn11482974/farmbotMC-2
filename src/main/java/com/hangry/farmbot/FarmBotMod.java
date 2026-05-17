package com.hangry.farmbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FarmBotMod implements ClientModInitializer {

    // ── Settings ──────────────────────────────────────────────────────────────
    public static boolean botActive = false;
    public static boolean showGui = true;
    public static float clickSpeedMin = 0.05f;
    public static float clickSpeedMax = 0.10f;
    public static int sessionLimitMinutes = 0;
    public static String webhookUrl = "";
    public static String minecraftUsername = "";
    public static boolean notifyActivityCheck = true;
    public static boolean notifySessionEnd = true;
    public static boolean notifyBotStart = false;

    // ── Bot state ─────────────────────────────────────────────────────────────
    private static boolean goingRight = true;
    private static int clickTickTimer = 0;
    private static int currentClickEvery = 2;
    private static int rowCount = 0;
    private static long startTime = 0;
    private static int clickCount = 0;
    private static int activityCheckCount = 0;

    private static double lastX = 0, lastZ = 0, lastY = 0;
    private static int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 8;

    private static int jumpHoldTicks = 0;
    private static int jumpCooldown = 0;
    private static final int JUMP_HOLD = 6;
    private static final int JUMP_COOLDOWN = 25;
    private static int postFlipPauseTicks = 0;

    // ── Balance tracking ──────────────────────────────────────────────────────
    private static long balanceBefore = 0;
    private static long balanceAfter = 0;
    private static long balanceCurrent = 0;
    private static boolean waitingForBalBefore = false;
    private static boolean waitingForBalAfter = false;
    private static int balCheckDelay = 0;
    private static final Pattern BAL_PATTERN =
        Pattern.compile("balance:\\s*\\$([\\d,]+)", Pattern.CASE_INSENSITIVE);

    // ── Session history ───────────────────────────────────────────────────────
    public static final List<SessionRecord> sessionHistory = new ArrayList<>();

    public static class SessionRecord {
        public String time, duration;
        public long profit;
        public SessionRecord(String time, String duration, long profit) {
            this.time = time; this.duration = duration; this.profit = profit;
        }
    }

    // ── Activity check ────────────────────────────────────────────────────────
    private static boolean wasHandledScreenOpen = false;
    private static long lastWebhookSent = 0;
    private static final long WEBHOOK_COOLDOWN_MS = 10000;

    // ── Keys ──────────────────────────────────────────────────────────────────
    private static KeyBinding toggleGuiKey;
    private static KeyBinding toggleBotKey;
    private static KeyBinding openConfigKey;

    private static final Random random = new Random();

    @Override
    public void onInitializeClient() {
        toggleGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.farmbot.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "FarmBot"));
        toggleBotKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.farmbot.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "FarmBot"));
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.farmbot.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "FarmBot"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register(this::renderHud);

        // Listen to chat for /bal response
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String raw = message.getString();
            Matcher m = BAL_PATTERN.matcher(raw);
            if (m.find()) {
                String numStr = m.group(1).replace(",", "");
                try {
                    long bal = Long.parseLong(numStr);
                    balanceCurrent = bal;
                    if (waitingForBalBefore) {
                        balanceBefore = bal;
                        waitingForBalBefore = false;
                    } else if (waitingForBalAfter) {
                        balanceAfter = bal;
                        waitingForBalAfter = false;
                        onBalanceAfterReceived();
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private static void pressKey(int keyCode, boolean pressed) {
        KeyBinding.setKeyPressed(InputUtil.fromKeyCode(keyCode, 0), pressed);
    }

    private static void stopMovement() {
        pressKey(GLFW.GLFW_KEY_A, false);
        pressKey(GLFW.GLFW_KEY_D, false);
    }

    private static void applyMovement() {
        if (goingRight) {
            pressKey(GLFW.GLFW_KEY_A, false);
            pressKey(GLFW.GLFW_KEY_D, true);
        } else {
            pressKey(GLFW.GLFW_KEY_D, false);
            pressKey(GLFW.GLFW_KEY_A, true);
        }
    }

    private static void randomizeClickSpeed() {
        float range = clickSpeedMax - clickSpeedMin;
        float speed = clickSpeedMin + random.nextFloat() * range;
        currentClickEvery = Math.max(1, (int)(speed / 0.05f));
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null) return;

        while (toggleGuiKey.wasPressed()) showGui = !showGui;
        while (openConfigKey.wasPressed()) client.setScreen(new FarmBotScreen(client.currentScreen));
        while (toggleBotKey.wasPressed()) {
            botActive = !botActive;
            if (botActive) startBot(client);
            else stopBot(client);
        }

        // Balance check delay
        if (balCheckDelay > 0) {
            balCheckDelay--;
            if (balCheckDelay == 0) {
                sendCommand(client, "/bal " + minecraftUsername);
            }
        }

        if (!botActive) return;

        // Session limit
        if (sessionLimitMinutes > 0) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000 / 60;
            if (elapsed >= sessionLimitMinutes) {
                botActive = false;
                stopBot(client);
                return;
            }
        }

        // ── Activity check detection ──────────────────────────────────────────
        boolean isHandledOpen = client.currentScreen instanceof HandledScreen;
        if (isHandledOpen && !wasHandledScreenOpen && botActive) {
            long now = System.currentTimeMillis();
            if (now - lastWebhookSent > WEBHOOK_COOLDOWN_MS) {
                activityCheckCount++;
                lastWebhookSent = now;
                stopMovement();
                botActive = false;
                pressKey(GLFW.GLFW_KEY_SPACE, false);
                jumpHoldTicks = 0;
                String sessionTime = formatTime((System.currentTimeMillis() - startTime) / 1000);
                if (notifyActivityCheck) {
                    sendWebhook(
                        "@everyone\\n" +
                        "⚠️ **ACTIVITY CHECK DETECTED!**\\n" +
                        "👤 Player: **" + minecraftUsername + "**\\n" +
                        "🔢 Check #" + activityCheckCount + "\\n" +
                        "⏱ Session: **" + sessionTime + "**\\n" +
                        "🌾 Rows: **" + rowCount + "** | Clicks: **" + clickCount + "**\\n" +
                        "💰 Balance: **$" + formatMoney(balanceCurrent) + "**\\n" +
                        "⚡ Bot **paused** — please respond!"
                    );
                }
                client.player.sendMessage(
                    Text.literal("§c[FarmBot] §eActivity check! Bot paused. Check Discord!"), false);
            }
        }
        wasHandledScreenOpen = isHandledOpen;

        if (!botActive) return;

        // ── Jump hold ─────────────────────────────────────────────────────────
        if (jumpHoldTicks > 0) {
            pressKey(GLFW.GLFW_KEY_SPACE, true);
            jumpHoldTicks--;
            if (jumpHoldTicks == 0) {
                pressKey(GLFW.GLFW_KEY_SPACE, false);
                jumpCooldown = JUMP_COOLDOWN;
            }
            doClickTick(client);
            lastX = client.player.getX();
            lastZ = client.player.getZ();
            lastY = client.player.getY();
            return;
        }
        if (jumpCooldown > 0) jumpCooldown--;

        // ── Post-flip pause ───────────────────────────────────────────────────
        if (postFlipPauseTicks > 0) {
            postFlipPauseTicks--;
            stopMovement();
            return;
        }

        double currentX = client.player.getX();
        double currentZ = client.player.getZ();
        double currentY = client.player.getY();

        double movement = Math.abs(currentX - lastX) + Math.abs(currentZ - lastZ);
        if (movement < 0.01) stuckTicks++;
        else stuckTicks = 0;

        if (stuckTicks >= STUCK_THRESHOLD) {
            stuckTicks = 0;
            stopMovement();
            double yDiff = lastY - currentY;
            if (yDiff <= 0.5 && jumpCooldown <= 0) {
                jumpHoldTicks = JUMP_HOLD;
                pressKey(GLFW.GLFW_KEY_SPACE, true);
            }
            flipDirection();
            rowCount++;
            postFlipPauseTicks = 5;
            lastX = currentX; lastZ = currentZ; lastY = currentY;
            return;
        }

        applyMovement();
        doClickTick(client);
        lastX = currentX; lastZ = currentZ; lastY = currentY;
    }

    private void doClickTick(MinecraftClient client) {
        clickTickTimer++;
        if (clickTickTimer >= currentClickEvery) {
            clickTickTimer = 0;
            randomizeClickSpeed();
            doClick(client);
            clickCount++;
        }
    }

    private void startBot(MinecraftClient client) {
        goingRight = true;
        rowCount = 0; clickCount = 0;
        startTime = System.currentTimeMillis();
        stuckTicks = 0; jumpHoldTicks = 0; jumpCooldown = 0;
        postFlipPauseTicks = 0; wasHandledScreenOpen = false;
        lastX = client.player.getX();
        lastZ = client.player.getZ();
        lastY = client.player.getY();
        randomizeClickSpeed();

        // Fetch balance before
        waitingForBalBefore = true;
        balCheckDelay = 20;

        String limitMsg = sessionLimitMinutes > 0
            ? " §7(limit: §e" + sessionLimitMinutes + "min§7)" : "";
        client.player.sendMessage(
            Text.literal("§a[FarmBot] Started! §7H§f=stop §7J§f=config" + limitMsg), true);

        if (notifyBotStart && !webhookUrl.isEmpty()) {
            sendWebhook(
                "🌾 **FarmBot Started!**\\n" +
                "👤 Player: **" + minecraftUsername + "**\\n" +
                "⏱ Session limit: **" +
                (sessionLimitMinutes > 0 ? sessionLimitMinutes + " min" : "Unlimited") + "**\\n" +
                "🖱 Click speed: **" + clickSpeedMin + "–" + clickSpeedMax + "s**"
            );
        }
    }

    private void stopBot(MinecraftClient client) {
        stopMovement();
        pressKey(GLFW.GLFW_KEY_SPACE, false);
        jumpHoldTicks = 0;

        // Fetch balance after
        waitingForBalAfter = true;
        balCheckDelay = 20;

        client.player.sendMessage(Text.literal("§c[FarmBot] Stopped. Fetching balance..."), true);
    }

    private void onBalanceAfterReceived() {
        long profit = balanceAfter - balanceBefore;
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        String duration = formatTime(elapsed);
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        long perMin = elapsed > 0 ? (profit * 60) / elapsed : 0;

        sessionHistory.add(0, new SessionRecord(time, duration, profit));
        if (sessionHistory.size() > 10) sessionHistory.remove(sessionHistory.size() - 1);

        if (notifySessionEnd && !webhookUrl.isEmpty()) {
            sendWebhook(
                "⏹ **FarmBot Session Complete!**\\n" +
                "👤 Player: **" + minecraftUsername + "**\\n" +
                "⏱ Duration: **" + duration + "**\\n" +
                "🌾 Rows: **" + rowCount + "** | Clicks: **" + clickCount + "**\\n" +
                "\\n" +
                "💰 Balance Before: **$" + formatMoney(balanceBefore) + "**\\n" +
                "💰 Balance After:  **$" + formatMoney(balanceAfter) + "**\\n" +
                "📈 Profit Made:    **+$" + formatMoney(profit) + "**\\n" +
                "⚡ Per Minute:     **$" + formatMoney(perMin) + "/min**"
            );
        }
    }

    private void flipDirection() { goingRight = !goingRight; }

    private void doClick(MinecraftClient client) {
        if (client.crosshairTarget != null &&
            client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            client.interactionManager.attackBlock(
                ((BlockHitResult) client.crosshairTarget).getBlockPos(),
                ((BlockHitResult) client.crosshairTarget).getSide()
            );
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private static void sendCommand(MinecraftClient client, String command) {
        if (client.player == null) return;
        client.player.networkHandler.sendCommand(command.substring(1));
    }

    private static String formatTime(long secs) {
        return String.format("%02d:%02d", secs / 60, secs % 60);
    }

    private static String formatMoney(long amount) {
        return String.format("%,d", amount);
    }

    private static void sendWebhook(String message) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        String json = "{\"content\":\"" + message + "\"}";
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    // ── HUD ───────────────────────────────────────────────────────────────────
    private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        if (!showGui) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int x = 10, y = 10, w = 200, h = botActive ? 175 : 60;
        context.fill(x, y, x + w, y + h, 0xCC0a0a1a);
        context.fill(x, y, x + w, y + 2, 0xFF6666dd);
        context.drawBorder(x, y, w, h, 0xFF2a2a6a);

        context.drawText(client.textRenderer,
            Text.literal("§b🌾 §fFarmBot §7| §eJ§f=open"),
            x + 6, y + 6, 0xFFFFFF, false);

        String status = botActive
            ? (jumpHoldTicks > 0 ? "§b● JUMPING" : "§a● RUNNING")
            : "§c● STOPPED";
        context.drawText(client.textRenderer,
            Text.literal(status), x + 6, y + 18, 0xFFFFFF, false);

        if (botActive) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            context.drawText(client.textRenderer,
                Text.literal("§7" + formatTime(elapsed) + " §8| §7rows:§f" + rowCount + " §8| §7clicks:§f" + clickCount),
                x + 6, y + 30, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7dir: §f" + (goingRight ? "→R" : "←L") +
                    " §8| §7jump: §f" + (jumpHoldTicks > 0 ? "§bHOLD" : jumpCooldown > 0 ? "§7cd" : "§aOK")),
                x + 6, y + 42, 0xFFFFFF, false);
            if (balanceCurrent > 0) {
                long profit = balanceCurrent - balanceBefore;
                context.drawText(client.textRenderer,
                    Text.literal("§7bal: §e$" + formatMoney(balanceCurrent) +
                        (profit > 0 ? " §a(+" + formatMoney(profit) + ")" : "")),
                    x + 6, y + 54, 0xFFFFFF, false);
            }
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7X:§f%.0f §7Z:§f%.0f §7Y:§f%.0f",
                    client.player.getX(), client.player.getZ(), client.player.getY())),
                x + 6, y + 66, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7checks: §e" + activityCheckCount),
                x + 6, y + 78, 0xFFFFFF, false);
        }
    }

    // ── Main GUI Screen ───────────────────────────────────────────────────────
    public static class FarmBotScreen extends Screen {
        private final Screen parent;
        private int activeTab = 0; // 0=dashboard, 1=config, 2=stats
        private TextFieldWidget clickMinField, clickMaxField, sessionField, webhookField, usernameField;

        public FarmBotScreen(Screen parent) {
            super(Text.literal("FarmBot"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2, cy = height / 2;
            int pw = 320, ph = 340;
            int px = cx - pw/2, py = cy - ph/2;

            // Config fields
            clickMinField = new TextFieldWidget(textRenderer, px + 12, py + 105, 140, 16, Text.literal(""));
            clickMinField.setText(String.valueOf(clickSpeedMin));
            clickMinField.setMaxLength(10);

            clickMaxField = new TextFieldWidget(textRenderer, px + 164, py + 105, 144, 16, Text.literal(""));
            clickMaxField.setText(String.valueOf(clickSpeedMax));
            clickMaxField.setMaxLength(10);

            sessionField = new TextFieldWidget(textRenderer, px + 12, py + 140, 140, 16, Text.literal(""));
            sessionField.setText(String.valueOf(sessionLimitMinutes));
            sessionField.setMaxLength(6);

            usernameField = new TextFieldWidget(textRenderer, px + 164, py + 140, 144, 16, Text.literal(""));
            usernameField.setText(minecraftUsername);
            usernameField.setMaxLength(32);

            webhookField = new TextFieldWidget(textRenderer, px + 12, py + 175, 296, 16, Text.literal(""));
            webhookField.setText(webhookUrl);
            webhookField.setMaxLength(512);

            // Tab buttons
            addDrawableChild(ButtonWidget.builder(Text.literal("Dashboard"),
                btn -> activeTab = 0).dimensions(px, py + 30, 107, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Config"),
                btn -> { activeTab = 1; addConfigFields(); }).dimensions(px + 107, py + 30, 106, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Stats"),
                btn -> activeTab = 2).dimensions(px + 213, py + 30, 107, 18).build());

            // Save button
            addDrawableChild(ButtonWidget.builder(Text.literal("Save & Close"), btn -> {
                saveConfig();
                close();
            }).dimensions(px + pw/2 - 60, py + ph - 26, 120, 18).build());
        }

        private void addConfigFields() {
            addDrawableChild(clickMinField);
            addDrawableChild(clickMaxField);
            addDrawableChild(sessionField);
            addDrawableChild(usernameField);
            addDrawableChild(webhookField);
        }

        private void saveConfig() {
            try { clickSpeedMin = Float.parseFloat(clickMinField.getText()); } catch (Exception ignored) {}
            try { clickSpeedMax = Float.parseFloat(clickMaxField.getText()); } catch (Exception ignored) {}
            try { sessionLimitMinutes = Integer.parseInt(sessionField.getText()); } catch (Exception ignored) {}
            minecraftUsername = usernameField.getText().trim();
            webhookUrl = webhookField.getText().trim();
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            int cx = width / 2, cy = height / 2;
            int pw = 320, ph = 340;
            int px = cx - pw/2, py = cy - ph/2;

            // Background panel
            ctx.fill(px, py, px + pw, py + ph, 0xEE0d0d1a);
            ctx.fill(px, py, px + pw, py + 3, 0xFF6666dd);
            ctx.drawBorder(px, py, pw, ph, 0xFF2a2a6a);

            // Title
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§b🌾 §fFarmBot"), cx, py + 10, 0xFFFFFF);

            // Tab indicator
            int[] tabX = {px, px+107, px+213};
            int[] tabW = {107, 106, 107};
            for (int i = 0; i < 3; i++) {
                if (activeTab == i) ctx.fill(tabX[i], py + 48, tabX[i] + tabW[i], py + 50, 0xFF6666dd);
            }

            // Content
            if (activeTab == 0) renderDashboard(ctx, px, py, pw);
            else if (activeTab == 1) renderConfig(ctx, px, py, pw);
            else renderStats(ctx, px, py, pw);

            super.render(ctx, mx, my, delta);
        }

        private void renderDashboard(DrawContext ctx, int px, int py, int pw) {
            MinecraftClient client = MinecraftClient.getInstance();
            int y = py + 58;

            // Status
            String status = botActive ? "§a● RUNNING" : "§c● STOPPED";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(status), px + pw/2, y, 0xFFFFFF);
            y += 16;

            // Start hint
            ctx.fill(px + 10, y, px + pw - 10, y + 24, 0xFF11112a);
            ctx.drawBorder(px + 10, y, pw - 20, 24, botActive ? 0xFF3a1a2a : 0xFF2a2a5a);
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal(botActive ? "§cStop Farming  §7[press H]" : "§7Start Farming  §8[press H]"),
                px + pw/2, y + 8, 0xFFFFFF);
            y += 34;

            // Stat grid
            String[][] stats = {
                {"Time", botActive ? formatTime((System.currentTimeMillis()-startTime)/1000) : "00:00"},
                {"Rows", String.valueOf(rowCount)},
                {"Clicks", String.valueOf(clickCount)},
                {"Checks", String.valueOf(activityCheckCount)}
            };
            for (int i = 0; i < 4; i++) {
                int sx = px + 10 + (i % 2) * 150;
                int sy = y + (i / 2) * 44;
                ctx.fill(sx, sy, sx + 145, sy + 38, 0xFF11112a);
                ctx.drawBorder(sx, sy, 145, 38, 0xFF1e1e3a);
                ctx.drawText(textRenderer, Text.literal("§7" + stats[i][0]), sx + 8, sy + 6, 0xFFFFFF, false);
                ctx.drawText(textRenderer, Text.literal("§f" + stats[i][1]), sx + 8, sy + 20, 0xFFFFFF, false);
            }
            y += 96;

            // Balance
            if (balanceCurrent > 0) {
                long profit = balanceCurrent - balanceBefore;
                ctx.fill(px + 10, y, px + pw - 10, y + 28, 0xFF0a1a0a);
                ctx.drawBorder(px + 10, y, pw - 20, 28, 0xFF1a4a2a);
                ctx.drawText(textRenderer,
                    Text.literal("§7Balance: §e$" + formatMoney(balanceCurrent) +
                        (profit > 0 ? "  §a(+" + formatMoney(profit) + ")" : "")),
                    px + 18, y + 10, 0xFFFFFF, false);
                y += 36;
            }

            // Position
            if (client.player != null) {
                ctx.drawText(textRenderer,
                    Text.literal(String.format("§7X:§f%.0f  §7Z:§f%.0f  §7Y:§f%.0f",
                        client.player.getX(), client.player.getZ(), client.player.getY())),
                    px + 18, y + 4, 0xFFFFFF, false);
            }
        }

        private void renderConfig(DrawContext ctx, int px, int py, int pw) {
            int y = py + 58;
            ctx.drawText(textRenderer, Text.literal("§7Click Speed Min"), px + 12, y + 4, 0xAAAAAA, false);
            ctx.drawText(textRenderer, Text.literal("§7Click Speed Max"), px + 164, y + 4, 0xAAAAAA, false);
            y += 14;
            ctx.fill(px + 12, y, px + 152, y + 16, 0xFF0a0a1a);
            ctx.fill(px + 164, y, px + 308, y + 16, 0xFF0a0a1a);
            y += 24;
            ctx.drawText(textRenderer, Text.literal("§7Session Limit (min)"), px + 12, y + 4, 0xAAAAAA, false);
            ctx.drawText(textRenderer, Text.literal("§7Minecraft Username"), px + 164, y + 4, 0xAAAAAA, false);
            y += 14;
            ctx.fill(px + 12, y, px + 152, y + 16, 0xFF0a0a1a);
            ctx.fill(px + 164, y, px + 308, y + 16, 0xFF0a0a1a);
            y += 24;
            ctx.drawText(textRenderer, Text.literal("§7Discord Webhook URL"), px + 12, y + 4, 0xAAAAAA, false);
            y += 14;
            ctx.fill(px + 12, y, px + 308, y + 16, 0xFF0a0a1a);
            y += 28;

            // Toggles
            String[][] toggles = {
                {"Ping @everyone on activity check", notifyActivityCheck ? "§aON" : "§cOFF"},
                {"Notify on session end", notifySessionEnd ? "§aON" : "§cOFF"},
                {"Notify on bot start", notifyBotStart ? "§aON" : "§cOFF"}
            };
            for (String[] t : toggles) {
                ctx.drawText(textRenderer, Text.literal("§7" + t[0]), px + 12, y + 4, 0xAAAAAA, false);
                ctx.drawText(textRenderer, Text.literal(t[1]), px + pw - 30, y + 4, 0xFFFFFF, false);
                y += 18;
            }
        }

        private void renderStats(DrawContext ctx, int px, int py, int pw) {
            int y = py + 58;

            long profit = balanceAfter > 0 ? balanceAfter - balanceBefore : 0;
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            long perMin = elapsed > 60 ? (profit * 60) / elapsed : 0;

            // Big profit card
            ctx.fill(px + 10, y, px + pw - 10, y + 50, 0xFF0a1a0a);
            ctx.drawBorder(px + 10, y, pw - 20, 50, 0xFF1a4a2a);
            ctx.drawText(textRenderer, Text.literal("§7Total Profit This Session"), px + 18, y + 8, 0x44aa44, false);
            ctx.drawText(textRenderer,
                Text.literal("§a+" + (profit > 0 ? "$" + formatMoney(profit) : "$0")),
                px + 18, y + 22, 0xFFFFFF, false);
            ctx.drawText(textRenderer,
                Text.literal("§7Per min: §a$" + formatMoney(perMin)),
                px + 18, y + 36, 0xFFFFFF, false);
            y += 58;

            // Balance rows
            ctx.fill(px + 10, y, px + pw - 10, y + 60, 0xFF0a0a1a);
            ctx.drawBorder(px + 10, y, pw - 20, 60, 0xFF1e1e3a);
            ctx.drawText(textRenderer, Text.literal("§7Before"), px + 18, y + 8, 0xAAAAAA, false);
            ctx.drawText(textRenderer,
                Text.literal(balanceBefore > 0 ? "§f$" + formatMoney(balanceBefore) : "§8—"),
                px + pw - 20 - textRenderer.getWidth(balanceBefore > 0 ? "$"+formatMoney(balanceBefore) : "—"), y + 8, 0xFFFFFF, false);
            ctx.fill(px + 18, y + 22, px + pw - 18, y + 23, 0xFF1a1a3a);
            ctx.drawText(textRenderer, Text.literal("§7After"), px + 18, y + 28, 0xAAAAAA, false);
            ctx.drawText(textRenderer,
                Text.literal(balanceAfter > 0 ? "§f$" + formatMoney(balanceAfter) : "§8—"),
                px + pw - 20 - textRenderer.getWidth(balanceAfter > 0 ? "$"+formatMoney(balanceAfter) : "—"), y + 28, 0xFFFFFF, false);
            ctx.fill(px + 18, y + 44, px + pw - 18, y + 45, 0xFF1a1a3a);
            ctx.drawText(textRenderer, Text.literal("§7Profit"), px + 18, y + 50, 0xAAAAAA, false);
            ctx.drawText(textRenderer,
                Text.literal(profit > 0 ? "§a+$" + formatMoney(profit) : "§8—"),
                px + pw - 20 - textRenderer.getWidth(profit > 0 ? "+$"+formatMoney(profit) : "—"), y + 50, 0xFFFFFF, false);
            y += 68;

            // History
            ctx.drawText(textRenderer, Text.literal("§7Session History"), px + 12, y, 0x4444aa, false);
            y += 12;
            if (sessionHistory.isEmpty()) {
                ctx.drawText(textRenderer, Text.literal("§8No sessions yet"), px + 12, y + 4, 0xFFFFFF, false);
            } else {
                for (int i = 0; i < Math.min(sessionHistory.size(), 4); i++) {
                    SessionRecord r = sessionHistory.get(i);
                    ctx.fill(px + 10, y, px + pw - 10, y + 20, 0xFF0a0a1a);
                    ctx.drawBorder(px + 10, y, pw - 20, 20, 0xFF1a1a3a);
                    ctx.drawText(textRenderer, Text.literal("§8" + r.time), px + 16, y + 6, 0xFFFFFF, false);
                    ctx.drawText(textRenderer, Text.literal("§a+$" + formatMoney(r.profit)), px + 90, y + 6, 0xFFFFFF, false);
                    ctx.drawText(textRenderer, Text.literal("§7" + r.duration), px + pw - 50, y + 6, 0xFFFFFF, false);
                    y += 24;
                }
            }
        }

        @Override
        public void close() { client.setScreen(parent); }
    }
}
