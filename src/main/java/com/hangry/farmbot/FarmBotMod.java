package com.hangry.farmbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
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

public class FarmBotMod implements ClientModInitializer {

    public static boolean botActive = false;
    public static boolean showGui = true;
    public static float clickSpeed = 0.10f;
    public static int sessionLimitMinutes = 0; // 0 = unlimited

    private static boolean goingRight = true;
    private static int clickTickTimer = 0;
    private static int rowCount = 0;
    private static long startTime = 0;
    private static int clickCount = 0;

    private static double lastX = 0, lastZ = 0, lastY = 0;
    private static int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 8;
    private static int jumpCooldown = 0;
    private static int postFlipPauseTicks = 0;

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

        // Session time limit check
        if (sessionLimitMinutes > 0) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000 / 60;
            if (elapsed >= sessionLimitMinutes) {
                botActive = false;
                stopBot(client);
                client.player.sendMessage(
                    Text.literal("§e[FarmBot] Session limit reached (" + sessionLimitMinutes + " min). Stopped!"), false);
                return;
            }
        }

        if (postFlipPauseTicks > 0) {
            postFlipPauseTicks--;
            client.player.input.movementSideways = 0f;
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
            double yDiff = lastY - currentY;
            if (yDiff <= 0.5 && jumpCooldown <= 0) {
                client.player.jump();
                jumpCooldown = 20;
            }
            flipDirection();
            rowCount++;
            postFlipPauseTicks = 5;
        }

        client.player.input.movementSideways = goingRight ? -1.0f : 1.0f;

        clickTickTimer++;
        int clickEvery = Math.max(1, (int)(clickSpeed / 0.05f));
        if (clickTickTimer >= clickEvery) {
            clickTickTimer = 0;
            doClick(client);
            clickCount++;
        }

        lastX = currentX;
        lastZ = currentZ;
        lastY = currentY;
        if (jumpCooldown > 0) jumpCooldown--;
    }

    private void startBot(MinecraftClient client) {
        goingRight = true;
        rowCount = 0;
        clickCount = 0;
        startTime = System.currentTimeMillis();
        stuckTicks = 0;
        jumpCooldown = 0;
        postFlipPauseTicks = 0;
        lastX = client.player.getX();
        lastZ = client.player.getZ();
        lastY = client.player.getY();
        String limitMsg = sessionLimitMinutes > 0 ? " §7(limit: §e" + sessionLimitMinutes + "min§7)" : "";
        client.player.sendMessage(
            Text.literal("§a[FarmBot] Started!§7 H=stop J=config" + limitMsg), true);
    }

    private void stopBot(MinecraftClient client) {
        client.player.input.movementSideways = 0f;
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

    private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        if (!showGui) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int x = 10, y = 10, w = 230, h = 185;
        context.fill(x, y, x + w, y + h, 0xCC0a0a1a);
        context.fill(x, y, x + w, y + 2, 0xFF00ff88);

        context.drawText(client.textRenderer,
            Text.literal("§a🌾 FarmBot"), x + 6, y + 6, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal("§7H§f=toggle  §7G§f=hud  §7J§f=config"),
            x + 6, y + 18, 0xAAAAAA, false);
        context.fill(x + 4, y + 30, x + w - 4, y + 31, 0xFF333355);

        String status = botActive ? "§a● RUNNING" : "§c● STOPPED";
        context.drawText(client.textRenderer, Text.literal(status), x + 6, y + 36, 0xFFFFFF, false);

        if (botActive) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            String timeStr = String.format("%02d:%02d", elapsed/60, elapsed%60);

            // Session limit progress
            String timeDisplay;
            if (sessionLimitMinutes > 0) {
                long limitSecs = sessionLimitMinutes * 60L;
                int pct = (int)((elapsed * 100) / limitSecs);
                timeDisplay = "§7Time: §f" + timeStr + " §7/ §e" + sessionLimitMinutes + "min §7(§f" + pct + "%§7)";
            } else {
                timeDisplay = "§7Time:   §f" + timeStr + " §7(unlimited)";
            }

            context.drawText(client.textRenderer, Text.literal(timeDisplay), x + 6, y + 49, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Rows:   §f" + rowCount), x + 6, y + 60, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Clicks: §f" + clickCount), x + 6, y + 70, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal("§7Dir:    §f" + (goingRight ? "→ Right" : "← Left")),
                x + 6, y + 80, 0xFFFFFF, false);
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Stuck:  §f%d§7/§f%d", stuckTicks, STUCK_THRESHOLD)),
                x + 6, y + 90, 0xFFFFFF, false);

            context.fill(x + 4, y + 102, x + w - 4, y + 103, 0xFF333355);
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7X:§f%.1f  §7Z:§f%.1f  §7Y:§f%.1f",
                    client.player.getX(), client.player.getZ(), client.player.getY())),
                x + 6, y + 107, 0xFFFFFF, false);
        }

        context.fill(x + 4, y + 120, x + w - 4, y + 121, 0xFF333355);
        context.drawText(client.textRenderer,
            Text.literal(String.format("§7Click Speed: §e%.2fs", clickSpeed)),
            x + 6, y + 125, 0xFFFFFF, false);
        context.drawText(client.textRenderer,
            Text.literal("§7Session Limit: §e" +
                (sessionLimitMinutes > 0 ? sessionLimitMinutes + " min" : "Unlimited")),
            x + 6, y + 136, 0xFFFFFF, false);

        context.drawBorder(x, y, w, h, 0xFF00ff44);
    }

    // ── Config Screen ──────────────────────────────────────────────────────────
    public static class FarmConfigScreen extends Screen {
        private final Screen parent;
        private TextFieldWidget clickField;
        private TextFieldWidget sessionField;

        public FarmConfigScreen(Screen parent) {
            super(Text.literal("FarmBot Config"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2;
            int cy = height / 2;

            clickField = new TextFieldWidget(textRenderer, cx - 80, cy - 30, 160, 20,
                Text.literal("Click Speed"));
            clickField.setText(String.valueOf(clickSpeed));
            clickField.setMaxLength(10);
            addDrawableChild(clickField);

            sessionField = new TextFieldWidget(textRenderer, cx - 80, cy + 10, 160, 20,
                Text.literal("Session Minutes"));
            sessionField.setText(String.valueOf(sessionLimitMinutes));
            sessionField.setMaxLength(6);
            addDrawableChild(sessionField);

            addDrawableChild(ButtonWidget.builder(Text.literal("✔ Save & Close"), btn -> {
                try { clickSpeed = Math.max(0.05f, Float.parseFloat(clickField.getText())); }
                catch (Exception ignored) {}
                try { sessionLimitMinutes = Math.max(0, Integer.parseInt(sessionField.getText())); }
                catch (Exception ignored) {}
                close();
            }).dimensions(cx - 80, cy + 40, 160, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("✖ Cancel"), btn -> close())
                .dimensions(cx - 80, cy + 65, 160, 20).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            renderBackground(context, mouseX, mouseY, delta);
            int cx = width / 2, cy = height / 2;

            context.fill(cx - 110, cy - 70, cx + 110, cy + 95, 0xDD0a0a1a);
            context.fill(cx - 110, cy - 70, cx + 110, cy - 68, 0xFF00ff88);
            context.drawBorder(cx - 110, cy - 70, 220, 165, 0xFF00ff44);

            context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§a🌾 FarmBot Config"), cx, cy - 62, 0xFFFFFF);

            context.drawTextWithShadow(textRenderer,
                Text.literal("§7Click Speed §8(sec, min 0.05):"), cx - 80, cy - 44, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer,
                Text.literal("§7Session Limit §8(minutes, 0=unlimited):"), cx - 80, cy - 4, 0xFFFFFF);

            super.render(context, mouseX, mouseY, delta);
        }

        @Override
        public void close() { client.setScreen(parent); }
    }
}
