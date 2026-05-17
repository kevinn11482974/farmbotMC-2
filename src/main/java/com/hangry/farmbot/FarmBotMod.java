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
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
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

        // Auto-detect username if not set
        if (minecraftUsername.isEmpty() && client.player != null) {
            minecraftUsername = client.player.getName().getString();
        }

        while (toggleGuiKey.wasPressed()) showGui = !showGui;
        while (openConfigKey.wasPressed()) client.setScreen(new FarmConfigScreen(client.currentScreen));
        while (toggleBotKey.wasPressed()) {
            botActive = !botActive;
            if (botActive) startBot(client);
            else stopBot(client);
        }

        // Balance check delay
        if (balCheckDelay > 0) {
            balCheckDelay--;
            if (balCheckDelay == 0) {
                String username = minecraftUsername.isEmpty()
                    ? client.player.getName().getString()
                    : minecraftUsername;
                client.player.networkHandler.sendCommand("bal " + username);
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
        if (isHandledOpen && !wasHandledScreenOpen) {
            long now = System.currentTimeMillis();
            if (now - lastWebhookSent > WEBHOOK_COOLDOWN_MS) {
                activityCheckCount++;
                lastWebhookSent = now;
                stopMovement();
                pressKey(GLFW.GLFW_KEY_SPACE, false);
                jumpHoldTicks = 0;
                botActive = false;
                String sessionTime = formatTime((System.currentTimeMillis() - startTime) / 1000);
                if (notifyActivityCheck) {
                    sendWebhook(
                        "@everyone\n⚠️ **ACTIVITY CHECK DETECTED!**\n" +
                        "👤 Player: **" + minecraftUsername + "**\n" +
                        "🔢 Check #" + activityCheckCount + "\n" +
                        "⏱ Session: **" + sessionTime + "**\n" +
                        "🌾 Rows: **" + rowCount + "** | Clicks: **" + clickCount + "**\n" +
                        "💰 Balance: **$" + formatMoney(balanceCurrent) + "**\n" +
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
        waitingForBalBefore = true;
        balCheckDelay = 20;

        String limitMsg = sessionLimitMinutes > 0
            ? " §7(limit: §e" + sessionLimitMinutes + "min§7)" : "";
        client.player.sendMessage(
            Text.literal("§a[FarmBot] Started! §7H§f=stop §7J§f=config" + limitMsg), true);

        if (notifyBotStart && !webhookUrl.isEmpty()) {
            sendWebhook(
                "🌾 **FarmBot Started!**\n" +
                "👤 Player: **" + minecraftUsername + "**\n" +
                "⏱ Session limit: **" +
                (sessionLimitMinutes > 0 ? sessionLimitMinutes + " min" : "Unlimited") + "**\n" +
                "🖱 Click speed: **" + clickSpeedMin + "–" + clickSpeedMax + "s**"
            );
        }
    }

    private void stopBot(MinecraftClient client) {
        stopMovement();
        pressKey(GLFW.GLFW_KEY_SPACE, false);
        jumpHoldTicks = 0;
        waitingForBalAfter = true;
        balCheckDelay = 20;
        client.player.sendMessage(
            Text.literal("§c[FarmBot] Stopped. Fetching balance..."), true);
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
                "⏹ **FarmBot Session Complete!**\n" +
                "👤 Player: **" + minecraftUsername + "**\n" +
                "⏱ Duration: **" + duration + "**\n" +
                "🌾 Rows: **" + rowCount + "** | Clicks: **" + clickCount + "**\n\n" +
                "💰 Balance Before: **$" + formatMoney(balanceBefore) + "**\n" +
                "💰 Balance After:  **$" + formatMoney(balanceAfter) + "**\n" +
                "📈 Profit Made:    **+$" + formatMoney(profit) + "**\n" +
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

    private static String formatTime(long secs) {
        return String.format("%02d:%02d", secs / 60, secs % 60);
    }

    private static String formatMoney(long amount) {
        return String.format("%,d", amount);
    }

    private static void sendWebhook(String message) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        String json = "{\"content\":\"" +
            message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") +
            "\"}";
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

        int x = 10, y = 10, w = 240, h = botActive ? 200 : 55;
        context.fill(x, y, x + w, y + h, 0xCC0a0a1a);
        context.fill(x, y, x + w, y + 2, 0xFF00ff88);
        context.drawBorder(x, y, w, h, 0xFF00ff44);

        context.drawText(client.textRenderer,
            Text.literal("§a🌾 FarmBot §7| §eH§f=toggle §eG§f=hud §eJ§f=config"),
            x + 6, y + 6, 0xFFFFFF, false);

        String status = botActive
            ? (jumpHoldTicks > 0 ? "§b● JUMPING" : "§a● RUNNING")
            : "§c● STOPPED";
        context.drawText(client.textRenderer,
            Text.literal(status), x + 6, y + 18, 0xFFFFFF, false);

        if (botActive) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Time:   §f%s%s", formatTime(elapsed),
                    sessionLimitMinutes > 0 ? " §7/ §e" + sessionLimitMinutes + "min" : "")),
                x + 6, y + 31, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Rows:   §f" + rowCount), x + 6, y + 41, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Clicks: §f" + clickCount), x + 6, y + 51, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Dir:    §f" + (goingRight ? "→ Right (D)" : "← Left (A)")),
                x + 6, y + 61, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Jump:   §f" + (jumpHoldTicks > 0
                    ? "§bHOLDING (" + jumpHoldTicks + ")"
                    : jumpCooldown > 0 ? "§7cooldown (" + jumpCooldown + ")" : "§aready")),
                x + 6, y + 71, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Stuck:  §f%d§7/§f%d", stuckTicks, STUCK_THRESHOLD)),
                x + 6, y + 81, 0xFFFFFF, false);
            context.fill(x + 4, y + 93, x + w - 4, y + 94, 0xFF333355);
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7X:§f%.1f §7Z:§f%.1f §7Y:§f%.1f",
                    client.player.getX(), client.player.getZ(), client.player.getY())),
                x + 6, y + 97, 0xFFFFFF, false);
            context.fill(x + 4, y + 109, x + w - 4, y + 110, 0xFF333355);
            if (balanceCurrent > 0) {
                long profit = balanceCurrent - balanceBefore;
                context.drawText(client.textRenderer,
                    Text.literal("§7Bal: §e$" + formatMoney(balanceCurrent)),
                    x + 6, y + 113, 0xFFFFFF, false);
                if (profit > 0) {
                    context.drawText(client.textRenderer,
                        Text.literal("§7Profit: §a+$" + formatMoney(profit)),
                        x + 6, y + 123, 0xFFFFFF, false);
                }
            }
            context.fill(x + 4, y + 136, x + w - 4, y + 137, 0xFF333355);
            context.drawText(client.textRenderer,
                Text.literal("§7Checks: §e" + activityCheckCount),
                x + 6, y + 140, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Webhook: " + (webhookUrl.isEmpty() ? "§cNot set" : "§aSet ✔")),
                x + 6, y + 150, 0xFFFFFF, false);

            // Session history summary
            if (!sessionHistory.isEmpty()) {
                context.fill(x + 4, y + 163, x + w - 4, y + 164, 0xFF333355);
                SessionRecord last = sessionHistory.get(0);
                context.drawText(client.textRenderer,
                    Text.literal("§7Last: §a+$" + formatMoney(last.profit) + " §7in §f" + last.duration),
                    x + 6, y + 167, 0xFFFFFF, false);
            }
        }
    }

    // ── Config Screen ─────────────────────────────────────────────────────────
    public static class FarmConfigScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget clickMinField, clickMaxField, sessionField, webhookField, usernameField;

        public FarmConfigScreen(Screen parent) {
            super(Text.literal("FarmBot Config"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2, cy = height / 2;

            clickMinField = new TextFieldWidget(textRenderer, cx - 100, cy - 90, 95, 16, Text.literal(""));
            clickMinField.setText(String.valueOf(clickSpeedMin));
            clickMinField.setMaxLength(10);
            addDrawableChild(clickMinField);

            clickMaxField = new TextFieldWidget(textRenderer, cx + 5, cy - 90, 95, 16, Text.literal(""));
            clickMaxField.setText(String.valueOf(clickSpeedMax));
            clickMaxField.setMaxLength(10);
            addDrawableChild(clickMaxField);

            sessionField = new TextFieldWidget(textRenderer, cx - 100, cy - 55, 95, 16, Text.literal(""));
            sessionField.setText(String.valueOf(sessionLimitMinutes));
            sessionField.setMaxLength(6);
            addDrawableChild(sessionField);

            usernameField = new TextFieldWidget(textRenderer, cx + 5, cy - 55, 95, 16, Text.literal(""));
            usernameField.setText(minecraftUsername);
            usernameField.setMaxLength(32);
            addDrawableChild(usernameField);

            webhookField = new TextFieldWidget(textRenderer, cx - 100, cy - 20, 200, 16, Text.literal(""));
            webhookField.setText(webhookUrl);
            webhookField.setMaxLength(512);
            addDrawableChild(webhookField);

            addDrawableChild(ButtonWidget.builder(Text.literal("✔ Save & Close"), btn -> {
                try { clickSpeedMin = Math.max(0.05f, Float.parseFloat(clickMinField.getText())); }
                catch (Exception ignored) {}
                try { clickSpeedMax = Math.max(clickSpeedMin, Float.parseFloat(clickMaxField.getText())); }
                catch (Exception ignored) {}
                try { sessionLimitMinutes = Math.max(0, Integer.parseInt(sessionField.getText())); }
                catch (Exception ignored) {}
                minecraftUsername = usernameField.getText().trim();
                webhookUrl = webhookField.getText().trim();
                close();
            }).dimensions(cx - 60, cy + 15, 120, 18).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("✖ Cancel"), btn -> close())
                .dimensions(cx - 60, cy + 38, 120, 18).build());

            // Stats button
            addDrawableChild(ButtonWidget.builder(Text.literal("📊 View Stats"), btn ->
                client.setScreen(new StatsScreen(this))
            ).dimensions(cx - 60, cy + 61, 120, 18).build());
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            int cx = width / 2, cy = height / 2;
            ctx.fill(cx - 115, cy - 110, cx + 115, cy + 88, 0xEE0a0a1a);
            ctx.fill(cx - 115, cy - 110, cx + 115, cy - 108, 0xFF00ff88);
            ctx.drawBorder(cx - 115, cy - 110, 230, 198, 0xFF00ff44);

            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§a🌾 FarmBot Config"), cx, cy - 103, 0xFFFFFF);

            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Click Min §8(sec):"), cx - 100, cy - 102, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Click Max §8(sec):"), cx + 5, cy - 102, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Session §8(min, 0=∞):"), cx - 100, cy - 67, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7MC Username:"), cx + 5, cy - 67, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Discord Webhook URL:"), cx - 100, cy - 32, 0xFFFFFF);

            super.render(ctx, mx, my, delta);
        }

        @Override
        public void close() { client.setScreen(parent); }
    }

    // ── Stats Screen ──────────────────────────────────────────────────────────
    public static class StatsScreen extends Screen {
        private final Screen parent;

        public StatsScreen(Screen parent) {
            super(Text.literal("FarmBot Stats"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2, cy = height / 2;
            addDrawableChild(ButtonWidget.builder(Text.literal("← Back"), btn -> close())
                .dimensions(cx - 60, cy + 90, 120, 18).build());
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            int cx = width / 2, cy = height / 2;
            ctx.fill(cx - 130, cy - 115, cx + 130, cy + 115, 0xEE0a0a1a);
            ctx.fill(cx - 130, cy - 115, cx + 130, cy - 113, 0xFF00ff88);
            ctx.drawBorder(cx - 130, cy - 115, 260, 230, 0xFF00ff44);

            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§a📊 Session Stats"), cx, cy - 108, 0xFFFFFF);

            long profit = balanceAfter > 0 ? balanceAfter - balanceBefore : 0;
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            long perMin = elapsed > 0 ? (profit * 60) / elapsed : 0;

            int y = cy - 90;
            ctx.fill(cx - 120, y, cx + 120, y + 40, 0xFF0a1a0a);
            ctx.drawBorder(cx - 120, y, 240, 40, 0xFF1a4a2a);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Total Profit"), cx - 112, y + 6, 0x44aa44);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§a+$" + formatMoney(Math.max(0, profit))), cx - 112, y + 20, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7$" + formatMoney(perMin) + "/min"), cx + 30, y + 20, 0x44aa44);
            y += 48;

            ctx.fill(cx - 120, y, cx + 120, y + 54, 0xFF0a0a1a);
            ctx.drawBorder(cx - 120, y, 240, 54, 0xFF1e1e3a);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7Before:"), cx - 112, y + 6, 0xAAAAAA);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal(balanceBefore > 0 ? "§f$" + formatMoney(balanceBefore) : "§8—"),
                cx + 20, y + 6, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7After:"), cx - 112, y + 22, 0xAAAAAA);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal(balanceAfter > 0 ? "§f$" + formatMoney(balanceAfter) : "§8—"),
                cx + 20, y + 22, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7Profit:"), cx - 112, y + 38, 0xAAAAAA);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal(profit > 0 ? "§a+$" + formatMoney(profit) : "§8—"),
                cx + 20, y + 38, 0xFFFFFF);
            y += 62;

            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Session History"), cx - 120, y, 0x4444aa);
            y += 12;

            if (sessionHistory.isEmpty()) {
                ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§8No sessions yet"), cx - 120, y, 0xFFFFFF);
            } else {
                for (int i = 0; i < Math.min(sessionHistory.size(), 4); i++) {
                    SessionRecord r = sessionHistory.get(i);
                    ctx.fill(cx - 120, y, cx + 120, y + 18, 0xFF0a0a1a);
                    ctx.drawBorder(cx - 120, y, 240, 18, 0xFF1a1a3a);
                    ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8" + r.time), cx - 114, y + 5, 0xFFFFFF);
                    ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§a+$" + formatMoney(r.profit)), cx - 60, y + 5, 0xFFFFFF);
                    ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§7" + r.duration), cx + 70, y + 5, 0xFFFFFF);
                    y += 22;
                }
            }
            super.render(ctx, mx, my, delta);
        }

        @Override
        public void close() { client.setScreen(parent); }
    }
}
