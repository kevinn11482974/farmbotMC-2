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
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.item.ShovelItem;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
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

    // ── Mode ──────────────────────────────────────────────────────────────────
    public enum BotMode { NONE, FARM, SNOW, HAWKJIGARFARMMEGAFASTVIPPRO }
    public static BotMode currentMode = BotMode.NONE;
    public static boolean botActive = false;
    public static boolean showGui = true;

    // ── Shared settings ───────────────────────────────────────────────────────
    public static float clickSpeedMin = 0.05f;
    public static float clickSpeedMax = 0.10f;
    public static int sessionLimitMinutes = 0;
    public static String webhookUrl = "";
    public static String minecraftUsername = "";
    public static boolean notifyActivityCheck = true;
    public static boolean notifySessionEnd = true;
    public static boolean notifyBotStart = false;

    // ── Snow settings ─────────────────────────────────────────────────────────
    public static int snowRows = 32;
    public static int snowRowWidth = 4;

    // ── Farm state ────────────────────────────────────────────────────────────
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

    // ── Snow state ────────────────────────────────────────────────────────────
    public enum SnowState { CLEARING, TELEPORTING }
    private static SnowState snowState = SnowState.CLEARING;
    private static boolean snowGoingRight = true;
    private static int snowRowCount = 0;
    private static int snowBlocksBroken = 0;
    private static int snowCycleCount = 0;
    private static int snowStuckTicks = 0;
    private static boolean snowSteppingForward = false;
    private static double snowForwardStartZ = 0;
    private static double snowLastX = 0, snowLastZ = 0;
    private static int snowClickTimer = 0;
    private static int snowCurrentClickEvery = 1;
    private static int snowTeleportDelay = 0;
    private static int snowFixTimer = 6000;

    // ── Hawk state ────────────────────────────────────────────────────────────
    private static boolean hawkGoingRight = true;
    private static int hawkStuckTicks = 0;
    private static int hawkFlipGrace = 0;
    private static int hawkBlocksBroken = 0;
    private static double hawkLastX = 0;
    private static double hawkLastZ = 0;

    // ── Activity solver state ─────────────────────────────────────────────────
    private static boolean activitySolverActive = false;
    private static String activityCurrentCmd = "";
    private static int activityCmdTicks = 0;
    private static float activityStartYaw = 0;
    private static float activityStartPitch = 0;
    private static int activityWaitTicks = 0;

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
        public String time, duration, mode;
        public long profit;
        public int kills;
        public SessionRecord(String t, String d, String m, long p, int k) {
            time=t; duration=d; mode=m; profit=p; kills=k;
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

    // ── Init ──────────────────────────────────────────────────────────────────
    @Override
    public void onInitializeClient() {
        toggleGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.farmbot.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "BotMaster"));
        toggleBotKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.farmbot.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "BotMaster"));
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.farmbot.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "BotMaster"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register(this::renderHud);

        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String raw = message.getString();
            // Balance
            Matcher balM = BAL_PATTERN.matcher(raw);
            if (balM.find()) {
                try {
                    long bal = Long.parseLong(balM.group(1).replace(",", ""));
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
            // Activity check auto-solver — detect server instructions
            if (activitySolverActive && activityCurrentCmd.isEmpty() && activityWaitTicks == 0) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    if      (raw.contains("Look Left"))                    startActivityCmd(mc.player, "Look Left");
                    else if (raw.contains("Look Right"))                   startActivityCmd(mc.player, "Look Right");
                    else if (raw.contains("Look Up"))                      startActivityCmd(mc.player, "Look Up");
                    else if (raw.contains("Look Down"))                    startActivityCmd(mc.player, "Look Down");
                    else if (raw.contains("Jump"))                         startActivityCmd(mc.player, "Jump");
                    else if (raw.contains("Sneak") || raw.contains("Crouch")) startActivityCmd(mc.player, "Sneak");
                    else if (raw.contains("Punch") || raw.contains("Hit"))    startActivityCmd(mc.player, "Punch");
                    else if (raw.contains("Click"))                        startActivityCmd(mc.player, "Click");
                }
            }
            // Weather vote (always active in SNOW mode)
            if (currentMode == BotMode.SNOW) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    if (raw.contains("A weather vote for Rain is already active")) {
                        mc.player.networkHandler.sendCommand("wv yes");
                    } else if (raw.contains("Weather vote for")) {
                        if (raw.contains("Rain")) mc.player.networkHandler.sendCommand("wv yes");
                        else mc.player.networkHandler.sendCommand("wv no");
                    }
                }
            }
        });
    }

    // ── Key helpers ───────────────────────────────────────────────────────────
    private static void pressKey(int k, boolean p) {
        KeyBinding.setKeyPressed(InputUtil.fromKeyCode(k, 0), p);
    }

    private static void stopAllMovement() {
        pressKey(GLFW.GLFW_KEY_A, false);
        pressKey(GLFW.GLFW_KEY_D, false);
        pressKey(GLFW.GLFW_KEY_W, false);
        pressKey(GLFW.GLFW_KEY_S, false);
    }

    // ── Main tick ─────────────────────────────────────────────────────────────
    private void onTick(MinecraftClient client) {
        if (client.player == null) return;

        // Auto detect username
        if (minecraftUsername.isEmpty())
            minecraftUsername = client.player.getName().getString();

        // Keybinds
        while (toggleGuiKey.wasPressed()) showGui = !showGui;
        while (openConfigKey.wasPressed())
            client.setScreen(new MainScreen(client.currentScreen));
        while (toggleBotKey.wasPressed()) {
            if (currentMode == BotMode.NONE) {
                client.player.sendMessage(
                    Text.literal("§c[BotMaster] Select a job first! Press J"), true);
                return;
            }
            botActive = !botActive;
            if (botActive) startBot(client);
            else stopBot(client);
        }

        // Balance delay
        if (balCheckDelay > 0) {
            balCheckDelay--;
            if (balCheckDelay == 0)
                client.player.networkHandler.sendCommand("bal " + minecraftUsername);
        }

        if (!botActive) return;

        // Session limit
        if (sessionLimitMinutes > 0) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000 / 60;
            if (elapsed >= sessionLimitMinutes) {
                botActive = false;
                stopBot(client);
                if (notifySessionEnd) sendWebhook(
                    "⏰ **Session limit reached!**\n" +
                    "👤 Player: **" + minecraftUsername + "**\n" +
                    "🎮 Mode: **" + currentMode.name() + "**\n" +
                    "⏱ Duration: **" + sessionLimitMinutes + " min**"
                );
                return;
            }
        }

        // Activity check detection
        boolean isHandledOpen = client.currentScreen instanceof HandledScreen;
        if (isHandledOpen && !wasHandledScreenOpen) {
            long now = System.currentTimeMillis();
            if (now - lastWebhookSent > WEBHOOK_COOLDOWN_MS) {
                activityCheckCount++;
                lastWebhookSent = now;
                stopAllMovement();
                pressKey(GLFW.GLFW_KEY_SPACE, false);
                jumpHoldTicks = 0;
                botActive = false;
                activitySolverActive = true;
                activityCurrentCmd = "";
                activityWaitTicks = 0;
                if (notifyActivityCheck) sendWebhook(
                    "@everyone\n" +
                    "⚠️ **ACTIVITY CHECK DETECTED!**\n" +
                    "👤 Player: **" + minecraftUsername + "**\n" +
                    "🎮 Mode: **" + currentMode.name() + "**\n" +
                    "🔢 Check #" + activityCheckCount + "\n" +
                    "⏱ Session: **" + formatTime((System.currentTimeMillis() - startTime) / 1000) + "**\n" +
                    "💰 Balance: **$" + formatMoney(balanceCurrent) + "**\n" +
                    "⚡ Bot **paused** — please respond!"
                );
                client.player.sendMessage(
                    Text.literal("§c[BotMaster] §eActivity check! Bot paused. Check Discord!"), false);
            }
        }
        // Deactivate solver when the activity-check screen closes
        if (!isHandledOpen && wasHandledScreenOpen && activitySolverActive) {
            activitySolverActive = false;
            activityCurrentCmd = "";
            pressKey(GLFW.GLFW_KEY_LEFT_SHIFT, false);
            pressKey(GLFW.GLFW_KEY_SPACE, false);
        }
        wasHandledScreenOpen = isHandledOpen;
        tickActivitySolver(client);
        if (!botActive) return;

        // Route to mode
        if      (currentMode == BotMode.FARM) tickFarm(client);
        else if (currentMode == BotMode.SNOW) tickSnow(client);
        else if (currentMode == BotMode.HAWKJIGARFARMMEGAFASTVIPPRO) tickHawk(client);
    }

    // ── Farm tick ─────────────────────────────────────────────────────────────
    private void tickFarm(MinecraftClient client) {
        // Jump hold
        if (jumpHoldTicks > 0) {
            pressKey(GLFW.GLFW_KEY_SPACE, true);
            jumpHoldTicks--;
            if (jumpHoldTicks == 0) {
                pressKey(GLFW.GLFW_KEY_SPACE, false);
                jumpCooldown = JUMP_COOLDOWN;
            }
            farmClick(client);
            lastX = client.player.getX();
            lastZ = client.player.getZ();
            lastY = client.player.getY();
            return;
        }
        if (jumpCooldown > 0) jumpCooldown--;

        // Post flip pause
        if (postFlipPauseTicks > 0) {
            postFlipPauseTicks--;
            pressKey(GLFW.GLFW_KEY_A, false);
            pressKey(GLFW.GLFW_KEY_D, false);
            return;
        }

        double cx = client.player.getX();
        double cz = client.player.getZ();
        double cy = client.player.getY();
        double movement = Math.abs(cx - lastX) + Math.abs(cz - lastZ);

        if (movement < 0.01) stuckTicks++;
        else stuckTicks = 0;

        // Hit wall — flip
        if (stuckTicks >= STUCK_THRESHOLD) {
            stuckTicks = 0;
            pressKey(GLFW.GLFW_KEY_A, false);
            pressKey(GLFW.GLFW_KEY_D, false);
            if (lastY - cy <= 0.5 && jumpCooldown <= 0) {
                jumpHoldTicks = JUMP_HOLD;
                pressKey(GLFW.GLFW_KEY_SPACE, true);
            }
            goingRight = !goingRight;
            rowCount++;
            postFlipPauseTicks = 5;
            lastX = cx; lastZ = cz; lastY = cy;
            return;
        }

        // Move
        if (goingRight) {
            pressKey(GLFW.GLFW_KEY_A, false);
            pressKey(GLFW.GLFW_KEY_D, true);
        } else {
            pressKey(GLFW.GLFW_KEY_D, false);
            pressKey(GLFW.GLFW_KEY_A, true);
        }

        farmClick(client);
        lastX = cx; lastZ = cz; lastY = cy;
    }

    private void farmClick(MinecraftClient client) {
        clickTickTimer++;
        if (clickTickTimer >= currentClickEvery) {
            clickTickTimer = 0;
            float speed = clickSpeedMin + random.nextFloat() * (clickSpeedMax - clickSpeedMin);
            currentClickEvery = Math.max(1, (int)(speed / 0.05f));
            if (client.crosshairTarget != null &&
                client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                client.interactionManager.attackBlock(
                    ((BlockHitResult) client.crosshairTarget).getBlockPos(),
                    ((BlockHitResult) client.crosshairTarget).getSide());
                client.player.swingHand(Hand.MAIN_HAND);
                clickCount++;
            }
        }
    }

    // ── Snow tick ─────────────────────────────────────────────────────────────
    private void tickSnow(MinecraftClient client) {
        if (!(client.player.getMainHandStack().getItem() instanceof ShovelItem)) {
            client.player.sendMessage(Text.literal("§c[BotMaster] Equip your shovel first!"), true);
            botActive = false;
            return;
        }
        if (client.world == null) return;

        // /fix all every 5 minutes
        if (--snowFixTimer <= 0) {
            client.player.networkHandler.sendCommand("fix all");
            snowFixTimer = 6000;
        }

        switch (snowState) {
            case CLEARING -> {
                // Click snow every tick
                snowClickTimer++;
                if (snowClickTimer >= snowCurrentClickEvery) {
                    snowClickTimer = 0;
                    float speed = clickSpeedMin + random.nextFloat() * (clickSpeedMax - clickSpeedMin);
                    snowCurrentClickEvery = Math.max(1, (int)(speed / 0.05f));
                    if (client.crosshairTarget != null &&
                            client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                        BlockHitResult bhr = (BlockHitResult) client.crosshairTarget;
                        client.interactionManager.attackBlock(bhr.getBlockPos(), bhr.getSide());
                        client.player.swingHand(Hand.MAIN_HAND);
                        snowBlocksBroken++;
                        clickCount++;
                    }
                }

                double cx = client.player.getX();
                double cz = client.player.getZ();

                if (snowSteppingForward) {
                    // Walking forward (Z axis) to step between rows
                    if (Math.abs(cz - snowForwardStartZ) >= snowRowWidth) {
                        stopAllMovement();
                        pressKey(GLFW.GLFW_KEY_SPACE, false);
                        snowSteppingForward = false;
                        snowGoingRight = !snowGoingRight;
                        snowRowCount++;
                        snowStuckTicks = 0;
                        snowLastX = cx;
                        snowLastZ = cz;
                        if (snowRowCount >= snowRows) {
                            stopAllMovement();
                            snowState = SnowState.TELEPORTING;
                            snowTeleportDelay = 0;
                        }
                    } else {
                        // Still stepping forward — jump if stuck
                        double moved = Math.abs(cz - snowLastZ);
                        if (moved < 0.01) {
                            snowStuckTicks++;
                            if (snowStuckTicks >= STUCK_THRESHOLD)
                                pressKey(GLFW.GLFW_KEY_SPACE, true);
                        } else {
                            snowStuckTicks = 0;
                            pressKey(GLFW.GLFW_KEY_SPACE, false);
                        }
                        pressKey(GLFW.GLFW_KEY_A, false);
                        pressKey(GLFW.GLFW_KEY_D, false);
                        pressKey(GLFW.GLFW_KEY_W, true);
                        snowLastZ = cz;
                    }
                } else {
                    // Walking sideways (X axis) through the row
                    double moved = Math.abs(cx - snowLastX);
                    if (moved < 0.01) snowStuckTicks++;
                    else snowStuckTicks = 0;

                    if (snowStuckTicks >= STUCK_THRESHOLD) {
                        // Hit wall — begin forward step
                        snowStuckTicks = 0;
                        stopAllMovement();
                        snowSteppingForward = true;
                        snowForwardStartZ = cz;
                        snowLastZ = cz;
                    } else {
                        if (snowGoingRight) {
                            pressKey(GLFW.GLFW_KEY_A, false);
                            pressKey(GLFW.GLFW_KEY_D, true);
                        } else {
                            pressKey(GLFW.GLFW_KEY_D, false);
                            pressKey(GLFW.GLFW_KEY_A, true);
                        }
                        snowLastX = cx;
                    }
                }
            }

            case TELEPORTING -> {
                stopAllMovement();
                if (snowTeleportDelay == 0) {
                    client.player.networkHandler.sendCommand("home snowfarmadmo");
                }
                snowTeleportDelay++;
                if (snowTeleportDelay >= 40) {
                    snowCycleCount++;
                    sendWebhook(
                        "❄️ **Snow cycle complete!**\n" +
                        "👤 Player: **" + minecraftUsername + "**\n" +
                        "🔢 Cycle: **#" + snowCycleCount + "**\n" +
                        "💎 Blocks broken: **" + snowBlocksBroken + "**\n" +
                        "↩ Restarting from row 1..."
                    );
                    snowRowCount = 0;
                    snowGoingRight = true;
                    snowSteppingForward = false;
                    snowStuckTicks = 0;
                    snowState = SnowState.CLEARING;
                }
            }
        }
    }

    // ── Hawk tick ─────────────────────────────────────────────────────────────
    private void tickHawk(MinecraftClient client) {
        if (client.world == null) return;

        // Scan 9x9x9 cube and break fully grown nether wart directly
        BlockPos origin = client.player.getBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos checkPos = origin.add(dx, dy, dz);
                    var bs = client.world.getBlockState(checkPos);
                    if (bs.getBlock() == Blocks.NETHER_WART &&
                            bs.get(NetherWartBlock.AGE) == 3) {
                        client.interactionManager.attackBlock(checkPos, Direction.UP);
                        hawkBlocksBroken++;
                        clickCount++;
                    }
                }
            }
        }

        // Hold one direction — detect wall by total XZ movement, then flip
        double cx = client.player.getX();
        double cz = client.player.getZ();
        double moved = Math.hypot(cx - hawkLastX, cz - hawkLastZ);

        if (hawkFlipGrace > 0) {
            hawkFlipGrace--;
            hawkStuckTicks = 0;
        } else {
            if (moved < 0.01) hawkStuckTicks++;
            else hawkStuckTicks = 0;

            if (hawkStuckTicks >= STUCK_THRESHOLD) {
                hawkGoingRight = !hawkGoingRight;
                hawkStuckTicks = 0;
                hawkFlipGrace = 20;
            }
        }

        if (hawkGoingRight) {
            pressKey(GLFW.GLFW_KEY_A, false);
            pressKey(GLFW.GLFW_KEY_D, true);
        } else {
            pressKey(GLFW.GLFW_KEY_D, false);
            pressKey(GLFW.GLFW_KEY_A, true);
        }
        hawkLastX = cx;
        hawkLastZ = cz;
    }

    // ── Activity solver ───────────────────────────────────────────────────────
    private void tickActivitySolver(MinecraftClient client) {
        if (!activitySolverActive || client.player == null) return;
        if (activityWaitTicks > 0) { activityWaitTicks--; return; }
        if (activityCurrentCmd.isEmpty()) return;

        activityCmdTicks++;
        switch (activityCurrentCmd) {
            case "Look Left" -> {
                float t = Math.min(1f, activityCmdTicks / 10f);
                client.player.setYaw(lerpAngle(activityStartYaw, activityStartYaw - 90f, t));
                if (activityCmdTicks >= 10) finishActivityCmd();
            }
            case "Look Right" -> {
                float t = Math.min(1f, activityCmdTicks / 10f);
                client.player.setYaw(lerpAngle(activityStartYaw, activityStartYaw + 90f, t));
                if (activityCmdTicks >= 10) finishActivityCmd();
            }
            case "Look Up" -> {
                float t = Math.min(1f, activityCmdTicks / 10f);
                client.player.setPitch(lerp(activityStartPitch, -45f, t));
                if (activityCmdTicks >= 10) finishActivityCmd();
            }
            case "Look Down" -> {
                float t = Math.min(1f, activityCmdTicks / 10f);
                client.player.setPitch(lerp(activityStartPitch, 45f, t));
                if (activityCmdTicks >= 10) finishActivityCmd();
            }
            case "Jump" -> {
                pressKey(GLFW.GLFW_KEY_SPACE, activityCmdTicks <= 5);
                if (activityCmdTicks >= 6) { pressKey(GLFW.GLFW_KEY_SPACE, false); finishActivityCmd(); }
            }
            case "Sneak" -> {
                pressKey(GLFW.GLFW_KEY_LEFT_SHIFT, activityCmdTicks <= 15);
                if (activityCmdTicks >= 16) { pressKey(GLFW.GLFW_KEY_LEFT_SHIFT, false); finishActivityCmd(); }
            }
            case "Punch" -> {
                if (client.crosshairTarget != null &&
                        client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult bhr = (BlockHitResult) client.crosshairTarget;
                    client.interactionManager.attackBlock(bhr.getBlockPos(), bhr.getSide());
                    client.player.swingHand(Hand.MAIN_HAND);
                }
                finishActivityCmd();
            }
            case "Click" -> {
                client.options.useKey.setPressed(true);
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                client.options.useKey.setPressed(false);
                finishActivityCmd();
            }
        }
    }

    private static void startActivityCmd(net.minecraft.entity.player.PlayerEntity player, String cmd) {
        activityCurrentCmd = cmd;
        activityCmdTicks = 0;
        activityStartYaw = player.getYaw();
        activityStartPitch = player.getPitch();
    }

    private static void finishActivityCmd() {
        activityCurrentCmd = "";
        activityCmdTicks = 0;
        activityWaitTicks = 3;
    }

    // ── Lerp helpers ──────────────────────────────────────────────────────────
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float lerpAngle(float a, float b, float t) {
        float diff = ((b - a) % 360 + 540) % 360 - 180;
        return a + diff * t;
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────
    private void startBot(MinecraftClient client) {
        if (currentMode == BotMode.SNOW &&
                !(client.player.getMainHandStack().getItem() instanceof ShovelItem)) {
            client.player.sendMessage(Text.literal("§c[BotMaster] Equip your shovel first!"), true);
            botActive = false;
            return;
        }
        startTime = System.currentTimeMillis();
        clickCount = 0;
        activityCheckCount = 0;
        wasHandledScreenOpen = false;

        waitingForBalBefore = true;
        balCheckDelay = 20;

        if (currentMode == BotMode.FARM) {
            goingRight = true; rowCount = 0; stuckTicks = 0;
            jumpHoldTicks = 0; jumpCooldown = 0; postFlipPauseTicks = 0;
            lastX = client.player.getX();
            lastZ = client.player.getZ();
            lastY = client.player.getY();
            float speed = clickSpeedMin + random.nextFloat() * (clickSpeedMax - clickSpeedMin);
            currentClickEvery = Math.max(1, (int)(speed / 0.05f));
        } else if (currentMode == BotMode.SNOW) {
            snowRowCount = 0; snowBlocksBroken = 0;
            snowState = SnowState.CLEARING; snowGoingRight = true;
            snowSteppingForward = false; snowStuckTicks = 0;
            snowTeleportDelay = 0; snowClickTimer = 0; snowCurrentClickEvery = 1;
            snowFixTimer = 6000;
            snowLastX = client.player.getX(); snowLastZ = client.player.getZ();
        } else if (currentMode == BotMode.HAWKJIGARFARMMEGAFASTVIPPRO) {
            hawkBlocksBroken = 0; hawkGoingRight = true;
            hawkStuckTicks = 0; hawkFlipGrace = 0;
            hawkLastX = client.player.getX(); hawkLastZ = client.player.getZ();
        }

        String emoji = currentMode == BotMode.FARM ? "🌾"
                     : currentMode == BotMode.SNOW ? "❄️"
                     : currentMode == BotMode.HAWKJIGARFARMMEGAFASTVIPPRO ? "⚡" : "🌱";
        client.player.sendMessage(
            Text.literal("§a[BotMaster] " + emoji + " " + currentMode.name() +
                " started! §7H§f=stop §7J§f=menu"), true);

        if (notifyBotStart && !webhookUrl.isEmpty()) {
            String detail = currentMode == BotMode.FARM
                ? "🖱 Click: **" + clickSpeedMin + "–" + clickSpeedMax + "s**"
                : currentMode == BotMode.SNOW
                ? "❄️ Rows: **" + snowRows + "** | Width: **" + snowRowWidth + "**"
                : currentMode == BotMode.HAWKJIGARFARMMEGAFASTVIPPRO
                ? "⚡ Nether wart 9x9x9 harvest"
                : "";
            sendWebhook(
                emoji + " **BotMaster Started — " + currentMode.name() + "**\n" +
                "👤 Player: **" + minecraftUsername + "**\n" +
                "⏱ Limit: **" +
                (sessionLimitMinutes > 0 ? sessionLimitMinutes + " min" : "Unlimited") + "**\n" +
                detail);
        }
    }

    private void stopBot(MinecraftClient client) {
        stopAllMovement();
        pressKey(GLFW.GLFW_KEY_SPACE, false);
        jumpHoldTicks = 0;
        waitingForBalAfter = true;
        balCheckDelay = 20;
        client.player.sendMessage(
            Text.literal("§c[BotMaster] Stopped. Fetching balance..."), true);
    }

    private void onBalanceAfterReceived() {
        long profit = balanceAfter - balanceBefore;
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        String duration = formatTime(elapsed);
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        long perMin = elapsed > 0 ? (profit * 60) / elapsed : 0;

        sessionHistory.add(0, new SessionRecord(time, duration, currentMode.name(), profit, 0));
        if (sessionHistory.size() > 10) sessionHistory.remove(sessionHistory.size() - 1);

        if (notifySessionEnd && !webhookUrl.isEmpty()) {
            String emoji = currentMode == BotMode.FARM ? "🌾"
                         : currentMode == BotMode.SNOW ? "❄️"
                         : "🌱";
            String extra = currentMode == BotMode.FARM
                ? "🌾 Rows: **" + rowCount + "** | Clicks: **" + clickCount + "**\n"
                : currentMode == BotMode.SNOW
                ? "❄️ Cycles: **" + snowCycleCount + "** | Blocks: **" + snowBlocksBroken + "**\n"
                : currentMode == BotMode.HAWKJIGARFARMMEGAFASTVIPPRO
                ? "⚡ Blocks: **" + hawkBlocksBroken + "**\n"
                : "";
            sendWebhook(
                emoji + " **Session Complete — " + currentMode.name() + "**\n" +
                "👤 Player: **" + minecraftUsername + "**\n" +
                "⏱ Duration: **" + duration + "**\n" + extra + "\n" +
                "💰 Before:  **$" + formatMoney(balanceBefore) + "**\n" +
                "💰 After:   **$" + formatMoney(balanceAfter) + "**\n" +
                "📈 Profit:  **+$" + formatMoney(profit) + "**\n" +
                "⚡ /min:    **$" + formatMoney(perMin) + "/min**"
            );
        }
    }

    // ── HUD ───────────────────────────────────────────────────────────────────
    private void renderHud(DrawContext ctx, RenderTickCounter tc) {
        if (!showGui) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        boolean isFarm = currentMode == BotMode.FARM;
        boolean isSnow = currentMode == BotMode.SNOW;
        boolean isHawk = currentMode == BotMode.HAWKJIGARFARMMEGAFASTVIPPRO;
        int x = 10, y = 10, w = 260;
        int h = !botActive ? 52 : isSnow ? 160 : isHawk ? 150 : 190;
        int accent = isFarm ? 0xFF00ff88 : isSnow ? 0xFF00ccff : isHawk ? 0xFFffaa00 : 0xFF6666dd;
        int border = isFarm ? 0xFF00ff44 : isSnow ? 0xFF0088aa : isHawk ? 0xFFaa6600 : 0xFF444488;

        ctx.fill(x, y, x+w, y+h, 0xCC0a0a1a);
        ctx.fill(x, y, x+w, y+2, accent);
        ctx.drawBorder(x, y, w, h, border);

        // Title bar
        String modeTag = currentMode == BotMode.NONE ? "§7No job" :
                         isFarm ? "§a🌾 Farm" : isSnow ? "§b❄ Snow" :
                         isHawk ? "§6⚡ HAWK" : "§7Unknown";
        ctx.drawText(client.textRenderer,
            Text.literal("§fBotMaster §8| " + modeTag + " §8| §eJ§f=menu §eH§f=toggle §eG§f=hud"),
            x+6, y+6, 0xFFFFFF, false);

        // Status
        String status = !botActive ? "§c● STOPPED" : "§a● RUNNING";
        ctx.drawText(client.textRenderer, Text.literal(status), x+6, y+18, 0xFFFFFF, false);

        if (!botActive) return;

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        ctx.drawText(client.textRenderer,
            Text.literal(String.format("§7Time: §f%s%s", formatTime(elapsed),
                sessionLimitMinutes > 0
                    ? " §7/ §e" + sessionLimitMinutes + "min §7(§f" +
                      (int)((elapsed * 100) / (sessionLimitMinutes * 60L)) + "%§7)"
                    : " §7(unlimited)")),
            x+6, y+31, 0xFFFFFF, false);

        if (isFarm) {
            ctx.drawText(client.textRenderer,
                Text.literal("§7Rows: §f" + rowCount + "  §7Clicks: §f" + clickCount),
                x+6, y+41, 0xFFFFFF, false);
            ctx.drawText(client.textRenderer,
                Text.literal("§7Dir: §f" + (goingRight ? "→R" : "←L") +
                    "  §7Jump: §f" + (jumpHoldTicks > 0 ? "§bHOLD" : jumpCooldown > 0 ? "§7cd" : "§aOK") +
                    "  §7Stuck: §f" + stuckTicks + "/" + STUCK_THRESHOLD),
                x+6, y+51, 0xFFFFFF, false);
            ctx.fill(x+4, y+63, x+w-4, y+64, 0xFF333355);
            if (balanceCurrent > 0) {
                long profit = balanceCurrent - balanceBefore;
                ctx.drawText(client.textRenderer,
                    Text.literal("§7Bal: §e$" + formatMoney(balanceCurrent) +
                        (profit > 0 ? "  §a(+$" + formatMoney(profit) + ")" : "")),
                    x+6, y+68, 0xFFFFFF, false);
            }
            ctx.drawText(client.textRenderer,
                Text.literal(String.format("§7X:§f%.0f §7Z:§f%.0f §7Y:§f%.0f",
                    client.player.getX(), client.player.getZ(), client.player.getY())),
                x+6, y+79, 0xFFFFFF, false);
            ctx.fill(x+4, y+91, x+w-4, y+92, 0xFF333355);
            ctx.drawText(client.textRenderer,
                Text.literal("§7Checks: §e" + activityCheckCount +
                    "  §7Webhook: " + (webhookUrl.isEmpty() ? "§cNot set" : "§aSet ✔")),
                x+6, y+95, 0xFFFFFF, false);
            if (!sessionHistory.isEmpty()) {
                SessionRecord last = sessionHistory.get(0);
                ctx.drawText(client.textRenderer,
                    Text.literal("§7Last: §a+$" + formatMoney(last.profit) +
                        " §7in §f" + last.duration),
                    x+6, y+106, 0xFFFFFF, false);
            }

        } else if (isSnow) {
            ctx.drawText(client.textRenderer,
                Text.literal("§7State: §b" + snowState.name() +
                    (snowState == SnowState.TELEPORTING ? " §8(ticking...)" : "")),
                x+6, y+41, 0xFFFFFF, false);
            ctx.drawText(client.textRenderer,
                Text.literal("§7Rows: §f" + snowRowCount + "§7/§f" + snowRows +
                    "  §7Cycle: §f#" + snowCycleCount),
                x+6, y+51, 0xFFFFFF, false);
            ctx.drawText(client.textRenderer,
                Text.literal("§7Blocks: §f" + snowBlocksBroken + " broken"),
                x+6, y+61, 0xFFFFFF, false);
            ctx.drawText(client.textRenderer,
                Text.literal("§7Dir: §f" + (snowGoingRight ? "→R" : "←L") +
                    (snowSteppingForward ? " §7(stepping fwd)" : "")),
                x+6, y+71, 0xFFFFFF, false);
            ctx.fill(x+4, y+83, x+w-4, y+84, 0xFF003344);
            ctx.drawText(client.textRenderer,
                Text.literal(String.format("§7X:§f%.0f §7Z:§f%.0f §7Y:§f%.0f",
                    client.player.getX(), client.player.getZ(), client.player.getY())),
                x+6, y+87, 0xFFFFFF, false);
            ctx.drawText(client.textRenderer,
                Text.literal("§7Checks: §e" + activityCheckCount +
                    "  §7Webhook: " + (webhookUrl.isEmpty() ? "§cNot set" : "§aSet ✔")),
                x+6, y+98, 0xFFFFFF, false);
            if (!sessionHistory.isEmpty()) {
                SessionRecord last = sessionHistory.get(0);
                ctx.drawText(client.textRenderer,
                    Text.literal("§7Last: §a+$" + formatMoney(last.profit) +
                        " §7in §f" + last.duration),
                    x+6, y+109, 0xFFFFFF, false);
            }

        } else if (isHawk) {
            long secs = Math.max(1, elapsed);
            long bpm = (hawkBlocksBroken * 60L) / secs;
            ctx.drawText(client.textRenderer,
                Text.literal("§7Blocks: §f" + hawkBlocksBroken + " broken"),
                x+6, y+41, 0xFFFFFF, false);
            ctx.drawText(client.textRenderer,
                Text.literal("§7Blocks/min: §6" + bpm),
                x+6, y+51, 0xFFFFFF, false);
            ctx.drawText(client.textRenderer,
                Text.literal("§7Dir: §f" + (hawkGoingRight ? "→R" : "←L")),
                x+6, y+61, 0xFFFFFF, false);
            ctx.fill(x+4, y+73, x+w-4, y+74, 0xFF332200);
            ctx.drawText(client.textRenderer,
                Text.literal(String.format("§7X:§f%.0f §7Z:§f%.0f §7Y:§f%.0f",
                    client.player.getX(), client.player.getZ(), client.player.getY())),
                x+6, y+77, 0xFFFFFF, false);
            ctx.drawText(client.textRenderer,
                Text.literal("§7Checks: §e" + activityCheckCount +
                    "  §7Webhook: " + (webhookUrl.isEmpty() ? "§cNot set" : "§aSet ✔")),
                x+6, y+88, 0xFFFFFF, false);
        }
    }

    // ── Main Screen ───────────────────────────────────────────────────────────
    public static class MainScreen extends Screen {
        private final Screen parent;
        private int view = 0;
        private TextFieldWidget clickMinF, clickMaxF, sessionF, webhookF, usernameF;
        private TextFieldWidget snowRowsF, snowRowWidthF;

        public MainScreen(Screen parent) {
            super(Text.literal("BotMaster"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width/2, cy = height/2;
            int pw = 340, ph = 350;
            int px = cx-pw/2, py = cy-ph/2;

            // Tabs
            addDrawableChild(ButtonWidget.builder(Text.literal("Job"),
                btn -> { view=0; clearAndInit(); }).dimensions(px, py+28, 114, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Config"),
                btn -> { view=1; clearAndInit(); }).dimensions(px+114, py+28, 112, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Stats"),
                btn -> { view=2; clearAndInit(); }).dimensions(px+226, py+28, 114, 18).build());

            if (view == 0) initJobView(px, py, pw, cx, cy);
            else if (view == 1) initConfigView(px, py, pw, cx, cy);

            addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> close())
                .dimensions(cx-40, py+ph-24, 80, 18).build());
        }

        protected void clearAndInit() { clearChildren(); init(); }

        private void initJobView(int px, int py, int pw, int cx, int cy) {
            // Row 1: Farm, Snow
            addDrawableChild(ButtonWidget.builder(Text.literal("🌾  Farming"),
                btn -> { currentMode = BotMode.FARM; clearAndInit(); })
                .dimensions(px+10, py+80, 150, 26).build());
            // Row 2: Hawk (centred)
            addDrawableChild(ButtonWidget.builder(Text.literal("❄️  Snowing"),
                btn -> { currentMode = BotMode.SNOW; clearAndInit(); })
                .dimensions(px+pw-160, py+80, 150, 26).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("⚡  HawkMode"),
                btn -> { currentMode = BotMode.HAWKJIGARFARMMEGAFASTVIPPRO; clearAndInit(); })
                .dimensions(cx-75, py+112, 150, 26).build());

            // Start/Stop
            boolean canStart = currentMode != BotMode.NONE;
            addDrawableChild(ButtonWidget.builder(
                Text.literal(botActive ? "⏹  Stop  [H]" :
                    canStart ? "▶  Start  [H]" : "— Select a job first —"),
                btn -> {
                    if (!canStart) return;
                    MinecraftClient c = MinecraftClient.getInstance();
                    if (c.player == null) return;
                    botActive = !botActive;
                    FarmBotMod mod = new FarmBotMod();
                    if (botActive) mod.startBot(c);
                    else mod.stopBot(c);
                    clearAndInit();
                }).dimensions(cx-80, py+152, 160, 24).build());
        }

        private void initConfigView(int px, int py, int pw, int cx, int cy) {
            int fy = py+68;

            // Farm
            clickMinF = addField(px+10, fy+12, 150, String.valueOf(clickSpeedMin));
            clickMaxF = addField(px+pw-160, fy+12, 150, String.valueOf(clickSpeedMax));
            fy += 36;

            sessionF = addField(px+10, fy+12, 150, String.valueOf(sessionLimitMinutes));
            fy += 36;

            // Snow
            snowRowsF     = addField(px+10, fy+12, 150, String.valueOf(snowRows));
            snowRowWidthF = addField(px+pw-160, fy+12, 150, String.valueOf(snowRowWidth));
            fy += 36;

            // Username
            usernameF = addField(px+10, fy+12, pw-20, minecraftUsername);
            fy += 36;

            // Webhook
            webhookF = addField(px+10, fy+12, pw-20, webhookUrl);
            fy += 28;

            // Notification toggles (auto-saved when closing)
            addDrawableChild(ButtonWidget.builder(
                Text.literal("Notify Start: " + (notifyBotStart ? "§aON" : "§cOFF")),
                btn -> { notifyBotStart = !notifyBotStart; clearAndInit(); })
                .dimensions(px+10, fy, 100, 16).build());
            addDrawableChild(ButtonWidget.builder(
                Text.literal("Notify Stop: " + (notifySessionEnd ? "§aON" : "§cOFF")),
                btn -> { notifySessionEnd = !notifySessionEnd; clearAndInit(); })
                .dimensions(px+120, fy, 100, 16).build());
            addDrawableChild(ButtonWidget.builder(
                Text.literal("Notify Check: " + (notifyActivityCheck ? "§aON" : "§cOFF")),
                btn -> { notifyActivityCheck = !notifyActivityCheck; clearAndInit(); })
                .dimensions(px+230, fy, 100, 16).build());
        }

        private TextFieldWidget addField(int x, int y, int w, String val) {
            TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 16, Text.literal(""));
            f.setText(val); f.setMaxLength(512);
            addDrawableChild(f);
            return f;
        }

        private void saveConfig() {
            try { clickSpeedMin = Math.max(0.05f, Float.parseFloat(clickMinF.getText())); } catch (Exception ignored) {}
            try { clickSpeedMax = Math.max(clickSpeedMin, Float.parseFloat(clickMaxF.getText())); } catch (Exception ignored) {}
            try { sessionLimitMinutes = Math.max(0, Integer.parseInt(sessionF.getText())); } catch (Exception ignored) {}
            try { snowRows = Math.max(1, Integer.parseInt(snowRowsF.getText())); } catch (Exception ignored) {}
            try { snowRowWidth = Math.max(1, Integer.parseInt(snowRowWidthF.getText())); } catch (Exception ignored) {}
            if (usernameF != null) minecraftUsername = usernameF.getText().trim();
            if (webhookF != null) webhookUrl = webhookF.getText().trim();
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            int cx = width/2, cy = height/2;
            int pw = 340, ph = 350;
            int px = cx-pw/2, py = cy-ph/2;
            int accent = currentMode==BotMode.FARM ? 0xFF00ff88 :
                         currentMode==BotMode.SNOW ? 0xFF00ccff :
                         currentMode==BotMode.HAWKJIGARFARMMEGAFASTVIPPRO ? 0xFFffaa00 : 0xFF6666dd;
            int border = currentMode==BotMode.FARM ? 0xFF00ff44 :
                         currentMode==BotMode.SNOW ? 0xFF0088aa :
                         currentMode==BotMode.HAWKJIGARFARMMEGAFASTVIPPRO ? 0xFFaa6600 : 0xFF444488;

            ctx.fill(px, py, px+pw, py+ph, 0xEE0a0a1a);
            ctx.fill(px, py, px+pw, py+3, accent);
            ctx.drawBorder(px, py, pw, ph, border);
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§fBotMaster"), cx, py+10, 0xFFFFFF);

            // Tab underline
            int[] tx = {px, px+114, px+226};
            int[] tw = {114, 112, 114};
            ctx.fill(tx[view], py+46, tx[view]+tw[view], py+48, accent);

            if (view == 0) renderJobView(ctx, px, py, pw, cx);
            else if (view == 1) renderConfigView(ctx, px, py, pw, cx);
            else renderStatsView(ctx, px, py, pw, cx);

            super.render(ctx, mx, my, delta);
        }

        private void renderJobView(DrawContext ctx, int px, int py, int pw, int cx) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7Select a job"), cx, py+68, 0x888888);

            // Row 1 card highlights
            if (currentMode==BotMode.FARM)
                ctx.fill(px+8, py+78, px+162, py+108, 0xFF0a1a0a);
            // Row 2 card highlights
            if (currentMode==BotMode.SNOW)
                ctx.fill(px+pw-162, py+78, px+pw-8, py+108, 0xFF001a22);

            if (currentMode==BotMode.HAWKJIGARFARMMEGAFASTVIPPRO)
                ctx.fill(px+8, py+110, px+pw-8, py+140, 0xFF221100);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§8harvest crops"), px+20, py+109, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§8break snow"), px+pw-145, py+109, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§8nether wart 9x9x9"), cx-50, py+141, 0xFFFFFF);

            ctx.fill(px+4, py+155, px+pw-4, py+156, 0xFF333355);

            String jobStr = currentMode==BotMode.NONE ? "§8None selected" :
                            currentMode==BotMode.FARM ? "§a🌾 Farming" :
                            currentMode==BotMode.SNOW ? "§b❄️ Snowing" :
                            currentMode==BotMode.HAWKJIGARFARMMEGAFASTVIPPRO ? "§6⚡ HawkMode" : "§8None selected";
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7Job: " + jobStr), cx, py+160, 0xFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7Status: " + (botActive ? "§a● Running" : "§c● Stopped")),
                cx, py+172, 0xFFFFFF);
            if (botActive) {
                long el = (System.currentTimeMillis() - startTime) / 1000;
                ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("§7Session: §f" + formatTime(el)), cx, py+184, 0xFFFFFF);
            }
        }

        private void renderConfigView(DrawContext ctx, int px, int py, int pw, int cx) {
            int fy = py+68;
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§a▸ Farm — Click Speed (sec)"), px+10, fy, 0x44aa44);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7Min:"), px+10, fy+4, 0xAAAAAA);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7Max:"), px+pw-160, fy+4, 0xAAAAAA);
            fy += 36;
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Session Limit (min, 0=∞):"), px+10, fy, 0xAAAAAA);
            fy += 36;
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§b▸ Snow Rows (default 32):"), px+10, fy, 0x0088aa);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§b▸ Snow Row Width (blocks):"), px+pw-160, fy, 0x0088aa);
            fy += 36;
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Minecraft Username:"), px+10, fy, 0xAAAAAA);
            fy += 36;
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Discord Webhook URL:"), px+10, fy, 0xAAAAAA);
        }

        private void renderStatsView(DrawContext ctx, int px, int py, int pw, int cx) {
            long profit = balanceAfter > 0 ? balanceAfter - balanceBefore : 0;
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            long perMin = elapsed > 0 ? (profit * 60) / elapsed : 0;
            int y = py+60;

            // Profit card
            ctx.fill(px+10, y, px+pw-10, y+52, 0xFF0a1a0a);
            ctx.drawBorder(px+10, y, pw-20, 52, 0xFF1a4a2a);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7Session Profit"), px+18, y+6, 0x44aa44);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§a+$" + formatMoney(Math.max(0, profit))), px+18, y+18, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7$" + formatMoney(perMin) + "/min"), px+18, y+30, 0x44aa44);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7❄ Snow: §f" + snowBlocksBroken +
                    "  §7⚡ Hawk: §f" + hawkBlocksBroken),
                px+18, y+42, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Before: §f$" + formatMoney(balanceBefore) +
                    "  §7After: §f$" + formatMoney(balanceAfter)),
                px+18, y+52, 0xFFFFFF);
            y += 60;

            // History
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Session History"), px+12, y, 0x4444aa);
            y += 14;
            if (sessionHistory.isEmpty()) {
                ctx.drawTextWithShadow(textRenderer,
                    Text.literal("§8No sessions yet"), px+12, y, 0xFFFFFF);
            } else {
                for (int i = 0; i < Math.min(sessionHistory.size(), 8); i++) {
                    SessionRecord r = sessionHistory.get(i);
                    ctx.fill(px+10, y, px+pw-10, y+18, 0xFF0a0a1a);
                    ctx.drawBorder(px+10, y, pw-20, 18, 0xFF1a1a3a);
                    ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8" + r.time + " §7[§f" + r.mode + "§7]"),
                        px+14, y+5, 0xFFFFFF);
                    ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§a+$" + formatMoney(r.profit)),
                        px+140, y+5, 0xFFFFFF);
                    if (r.kills > 0)
                        ctx.drawTextWithShadow(textRenderer,
                            Text.literal("§7⚔" + r.kills), px+230, y+5, 0xFFFFFF);
                    ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§7" + r.duration), px+pw-55, y+5, 0xFFFFFF);
                    y += 22;
                }
            }
        }

        @Override
        public void close() { if (view == 1) saveConfig(); client.setScreen(parent); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static String formatTime(long s) {
        return String.format("%02d:%02d", s/60, s%60);
    }

    private static String formatMoney(long a) {
        return String.format("%,d", a);
    }

    private static void sendWebhook(String message) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        String json = "{\"content\":\"" +
            message.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n") + "\"}";
        HttpClient.newHttpClient().sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
