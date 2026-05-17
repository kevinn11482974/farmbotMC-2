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

public class FarmBotMod implements ClientModInitializer {

    public static boolean botActive = false;
    public static boolean showGui = true;
    public static float clickSpeed = 0.10f;
    public static int sessionLimitMinutes = 0;
    public static String webhookUrl = "";

    private static boolean goingRight = true;
    private static int clickTickTimer = 0;
    private static int rowCount = 0;
    private static long startTime = 0;
    private static int clickCount = 0;

    private static double lastX = 0, lastZ = 0, lastY = 0;
    private static int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 8;

    private static int jumpHoldTicks = 0;
    private static int jumpCooldown = 0;
    private static final int JUMP_HOLD = 6;
    private static final int JUMP_COOLDOWN = 25;
    private static int postFlipPauseTicks = 0;

    // Activity check
    private static boolean wasScreenOpen = false;
    private static long lastWebhookSent = 0;
    private static final long WEBHOOK_COOLDOWN_MS = 10000;
    private static int activityCheckCount = 0;

    private static KeyBinding toggleGuiKey;
    private static KeyBinding toggleBotKey;
    private static KeyBinding openConfigKey;

    @Override
    public void onInitializeClient() {
        toggleGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "FarmBot"
        ));
        toggleBotKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "FarmBot"
        ));
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.farmbot.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "FarmBot"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register(this::renderHud);
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

    private void onTick(MinecraftClient client) {
        if (client.player == null) return;

        while (toggleGuiKey.wasPressed()) showGui = !showGui;
        while (openConfigKey.wasPressed()) client.setScreen(new FarmConfigScreen(client.currentScreen));
        while (toggleBotKey.wasPressed()) {
            botActive = !botActive;
            if (botActive) startBot(client);
            else stopBot(client);
        }

        if (!botActive) return;

        // Session limit
        if (sessionLimitMinutes > 0) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000 / 60;
            if (elapsed >= sessionLimitMinutes) {
                botActive = false;
                stopBot(client);
                client.player.sendMessage(
                    Text.literal("§e[FarmBot] Session limit reached (" + sessionLimitMinutes + "min). Stopped!"), false);
                sendWebhook("⏰ **Session limit reached!** Bot stopped after " + sessionLimitMinutes + " minutes.\n**Rows:** " + rowCount + " | **Clicks:** " + clickCount);
                return;
            }
        }

        // ── Activity check detection ──────────────────────────────────────────
        boolean isScreenOpen = client.currentScreen instanceof HandledScreen;
        if (isScreenOpen && !wasScreenOpen) {
            long now = System.currentTimeMillis();
            if (now - lastWebhookSent > WEBHOOK_COOLDOWN_MS) {
                activityCheckCount++;
                lastWebhookSent = now;
                stopMovement();
                String playerName = client.player.getName().getString();
                sendWebhook(
                    "@everyone ⚠️ **ACTIVITY CHECK DETECTED!**\n" +
                    "👤 Player: **" + playerName + "**\n" +
                    "🔢 Check #" + activityCheckCount + "\n" +
                    "📊 Session: **" + formatTime((System.currentTimeMillis() - startTime) / 1000) + "**\n" +
                    "🌾 Rows: **" + rowCount + "** | Clicks: **" + clickCount + "**\n" +
                    "⚡ Bot has been **paused** — please respond to the check!"
                );
                botActive = false;
                client.player.sendMessage(
                    Text.literal("§c[FarmBot] §eActivity check detected! Bot paused. Check Discord!"), false);
            }
        }
        wasScreenOpen = isScreenOpen;

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
            lastX = currentX;
            lastZ = currentZ;
            lastY = currentY;
            return;
        }

        applyMovement();
        doClickTick(client);

        lastX = currentX;
        lastZ = currentZ;
        lastY = currentY;
    }

    private void doClickTick(MinecraftClient client) {
        clickTickTimer++;
        int clickEvery = Math.max(1, (int)(clickSpeed / 0.05f));
        if (clickTickTimer >= clickEvery) {
            clickTickTimer = 0;
            doClick(client);
            clickCount++;
        }
    }

    private void startBot(MinecraftClient client) {
        goingRight = true;
        rowCount = 0;
        clickCount = 0;
        startTime = System.currentTimeMillis();
        stuckTicks = 0;
        jumpHoldTicks = 0;
        jumpCooldown = 0;
        postFlipPauseTicks = 0;
        wasScreenOpen = false;
        lastX = client.player.getX();
        lastZ = client.player.getZ();
        lastY = client.player.getY();
        String limitMsg = sessionLimitMinutes > 0
            ? " §7(limit: §e" + sessionLimitMinutes + "min§7)" : "";
        client.player.sendMessage(
            Text.literal("§a[FarmBot] Started! §7H§f=stop §7J§f=config" + limitMsg), true);
        if (!webhookUrl.isEmpty()) {
            sendWebhook("🌾 **FarmBot Started!**\n👤 Player: **" +
                client.player.getName().getString() + "**\n⏱ Session limit: **" +
                (sessionLimitMinutes > 0 ? sessionLimitMinutes + " min" : "Unlimited") + "**");
        }
    }

    private void stopBot(MinecraftClient client) {
        stopMovement();
        pressKey(GLFW.GLFW_KEY_SPACE, false);
        jumpHoldTicks = 0;
        client.player.sendMessage(Text.literal("§c[FarmBot] Stopped."), true);
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

    private static void sendWebhook(String message) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        String json = "{\"content\":\"" + message.replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        if (!showGui) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int x = 10, y = 10, w = 240, h = 220;
        context.fill(x, y, x + w, y + h, 0xCC0a0a1a);
        context.fill(x, y, x + w, y + 2, 0xFF00ff88);

        context.drawText(client.textRenderer,
            Text.literal("§a🌾 FarmBot"), x + 6, y + 6, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal("§7H§f=toggle  §7G§f=hud  §7J§f=config"),
            x + 6, y + 18, 0xAAAAAA, false);
        context.fill(x + 4, y + 30, x + w - 4, y + 31, 0xFF333355);

        String status = botActive
            ? (jumpHoldTicks > 0 ? "§b● JUMPING" : "§a● RUNNING")
            : "§c● STOPPED";
        context.drawText(client.textRenderer, Text.literal(status), x + 6, y + 36, 0xFFFFFF, false);

        if (botActive) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            String timeStr = formatTime(elapsed);
            String timeDisplay = sessionLimitMinutes > 0
                ? "§7Time: §f" + timeStr + " §7/§e " + sessionLimitMinutes + "min §7(§f" + (int)((elapsed * 100) / (sessionLimitMinutes * 60L)) + "%§7)"
                : "§7Time:   §f" + timeStr + " §7(unlimited)";
            context.drawText(client.textRenderer, Text.literal(timeDisplay), x + 6, y + 49, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Rows:   §f" + rowCount), x + 6, y + 60, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Clicks: §f" + clickCount), x + 6, y + 70, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Dir:    §f" + (goingRight ? "→ Right (D)" : "← Left (A)")),
                x + 6, y + 80, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Stuck:  §f%d§7/§f%d ticks", stuckTicks, STUCK_THRESHOLD)),
                x + 6, y + 90, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Jump:   §f" + (jumpHoldTicks > 0
                    ? "§bHOLDING (" + jumpHoldTicks + ")"
                    : jumpCooldown > 0 ? "§7cooldown (" + jumpCooldown + ")" : "§aready")),
                x + 6, y + 100, 0xFFFFFF, false);
            context.fill(x + 4, y + 112, x + w - 4, y + 113, 0xFF333355);
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7X:§f%.1f §7Z:§f%.1f §7Y:§f%.1f",
                    client.player.getX(), client.player.getZ(), client.player.getY())),
                x + 6, y + 117, 0xFFFFFF, false);
        }

        context.fill(x + 4, y + 130, x + w - 4, y + 131, 0xFF333355);
        context.drawText(client.textRenderer,
            Text.literal(String.format("§7Click Speed:   §e%.2fs", clickSpeed)),
            x + 6, y + 135, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal("§7Session Limit: §e" +
                (sessionLimitMinutes > 0 ? sessionLimitMinutes + " min" : "Unlimited")),
            x + 6, y + 146, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal("§7Activity Checks: §e" + activityCheckCount),
            x + 6, y + 157, 0xFFFFFF, false);

        // Webhook status
        String webhookStatus = webhookUrl.isEmpty() ? "§cNot set" : "§aSet ✔";
        context.drawText(client.textRenderer,
            Text.literal("§7Webhook: " + webhookStatus),
            x + 6, y + 168, 0xFFFFFF, false);

        context.drawBorder(x, y, w, h, 0xFF00ff44);
    }

    public static class FarmConfigScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget clickField;
        private TextFieldWidget sessionField;
        private TextFieldWidget webhookField;

        public FarmConfigScreen(Screen parent) {
            super(Text.literal("FarmBot Config"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2, cy = height / 2;

            clickField = new TextFieldWidget(textRenderer, cx - 100, cy - 50, 200, 20,
                Text.literal("Click Speed"));
            clickField.setText(String.valueOf(clickSpeed));
            clickField.setMaxLength(10);
            addDrawableChild(clickField);

            sessionField = new TextFieldWidget(textRenderer, cx - 100, cy - 10, 200, 20,
                Text.literal("Session Minutes"));
            sessionField.setText(String.valueOf(sessionLimitMinutes));
            sessionField.setMaxLength(6);
            addDrawableChild(sessionField);

            webhookField = new TextFieldWidget(textRenderer, cx - 100, cy + 30, 200, 20,
                Text.literal("Discord Webhook URL"));
            webhookField.setText(webhookUrl);
            webhookField.setMaxLength(512);
            addDrawableChild(webhookField);

            addDrawableChild(ButtonWidget.builder(Text.literal("✔ Save & Close"), btn -> {
                try { clickSpeed = Math.max(0.05f, Float.parseFloat(clickField.getText())); }
                catch (Exception ignored) {}
                try { sessionLimitMinutes = Math.max(0, Integer.parseInt(sessionField.getText())); }
                catch (Exception ignored) {}
                webhookUrl = webhookField.getText().trim();
                close();
            }).dimensions(cx - 100, cy + 60, 200, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("✖ Cancel"), btn -> close())
                .dimensions(cx - 100, cy + 85, 200, 20).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            renderBackground(context, mouseX, mouseY, delta);
            int cx = width / 2, cy = height / 2;

            context.fill(cx - 120, cy - 80, cx + 120, cy + 115, 0xDD0a0a1a);
            context.fill(cx - 120, cy - 80, cx + 120, cy - 78, 0xFF00ff88);
            context.drawBorder(cx - 120, cy - 80, 240, 195, 0xFF00ff44);

            context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§a🌾 FarmBot Config"), cx, cy - 72, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer,
                Text.literal("§7Click Speed §8(sec, min 0.05):"), cx - 100, cy - 64, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer,
                Text.literal("§7Session Limit §8(minutes, 0=unlimited):"), cx - 100, cy - 24, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer,
                Text.literal("§7Discord Webhook URL:"), cx - 100, cy + 16, 0xFFFFFF);

            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void close() { client.setScreen(parent); }
    }
}
