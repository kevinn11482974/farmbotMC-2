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
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FarmBotMod implements ClientModInitializer {

    // ── Mode ──────────────────────────────────────────────────────────────────
    public enum BotMode { NONE, FARM, SLAY }
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

    // ── Slay settings ─────────────────────────────────────────────────────────
    public static float slayCpsMin = 3f;
    public static float slayCpsMax = 7f;
    public static float spinSpeed = 3.0f;
    public static int backpackAlertPercent = 95;
    public static float minDistance = 2.0f;
    public static float maxDistance = 4.0f;

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

    // ── Slay state ────────────────────────────────────────────────────────────
    private static int slayKills = 0;
    private static int slayClickTimer = 0;
    private static int slayClickEvery = 6;
    private static int strafeDir = 1; // 1 = right, -1 = left
    private static int strafeSwitchTimer = 0;
    private static int backpackCurrent = 0;
    private static int backpackMax = 0;
    private static boolean backpackAlertSent = false;
    private static String aimState = "scanning";
    private static float yawWobble = 0f;
    private static int wobbleTimer = 0;

    // ── Balance tracking ──────────────────────────────────────────────────────
    private static long balanceBefore = 0;
    private static long balanceAfter = 0;
    private static long balanceCurrent = 0;
    private static boolean waitingForBalBefore = false;
    private static boolean waitingForBalAfter = false;
    private static int balCheckDelay = 0;
    private static final Pattern BAL_PATTERN =
        Pattern.compile("balance:\\s*\\$([\\d,]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BACKPACK_PATTERN =
        Pattern.compile("(\\d[\\d,]*)/(\\d[\\d,]*)");

    // ── Session history ───────────────────────────────────────────────────────
    public static final List<SessionRecord> sessionHistory = new ArrayList<>();
    public static class SessionRecord {
        public String time, duration, mode;
        public long profit;
        public int kills;
        public SessionRecord(String time, String duration, String mode, long profit, int kills) {
            this.time = time; this.duration = duration;
            this.mode = mode; this.profit = profit; this.kills = kills;
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
            "key.farmbot.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "BotMaster"));
        toggleBotKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.farmbot.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "BotMaster"));
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.farmbot.config", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "BotMaster"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register(this::renderHud);

        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String raw = message.getString();
            Matcher balM = BAL_PATTERN.matcher(raw);
            if (balM.find()) {
                try {
                    long bal = Long.parseLong(balM.group(1).replace(",", ""));
                    balanceCurrent = bal;
                    if (waitingForBalBefore) { balanceBefore = bal; waitingForBalBefore = false; }
                    else if (waitingForBalAfter) { balanceAfter = bal; waitingForBalAfter = false; onBalanceAfterReceived(); }
                } catch (Exception ignored) {}
            }
            if (overlay && raw.contains("Backpack")) {
                Matcher bpM = BACKPACK_PATTERN.matcher(raw);
                if (bpM.find()) {
                    try {
                        backpackCurrent = Integer.parseInt(bpM.group(1).replace(",", ""));
                        backpackMax = Integer.parseInt(bpM.group(2).replace(",", ""));
                        checkBackpackFull();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    // ── Key helpers ───────────────────────────────────────────────────────────
    private static void pressKey(int keyCode, boolean pressed) {
        KeyBinding.setKeyPressed(InputUtil.fromKeyCode(keyCode, 0), pressed);
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

        if (minecraftUsername.isEmpty())
            minecraftUsername = client.player.getName().getString();

        while (toggleGuiKey.wasPressed()) showGui = !showGui;
        while (openConfigKey.wasPressed()) client.setScreen(new MainScreen(client.currentScreen));
        while (toggleBotKey.wasPressed()) {
            botActive = !botActive;
            if (botActive) startBot(client);
            else stopBot(client);
        }

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
                return;
            }
        }

        // Activity check
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
                if (notifyActivityCheck) {
                    sendWebhook(
                        "@everyone\n⚠️ **ACTIVITY CHECK DETECTED!**\n" +
                        "👤 Player: **" + minecraftUsername + "**\n" +
                        "🎮 Mode: **" + currentMode.name() + "**\n" +
                        "🔢 Check #" + activityCheckCount + "\n" +
                        "⏱ Session: **" + formatTime((System.currentTimeMillis() - startTime) / 1000) + "**\n" +
                        "⚡ Bot **paused** — please respond!"
                    );
                }
                client.player.sendMessage(
                    Text.literal("§c[BotMaster] §eActivity check! Bot paused. Check Discord!"), false);
            }
        }
        wasHandledScreenOpen = isHandledOpen;
        if (!botActive) return;

        // Route to correct mode
        if (currentMode == BotMode.FARM) tickFarm(client);
        else if (currentMode == BotMode.SLAY) tickSlay(client);
    }

    // ── Farm tick ─────────────────────────────────────────────────────────────
    private void tickFarm(MinecraftClient client) {
        if (jumpHoldTicks > 0) {
            pressKey(GLFW.GLFW_KEY_SPACE, true);
            jumpHoldTicks--;
            if (jumpHoldTicks == 0) {
                pressKey(GLFW.GLFW_KEY_SPACE, false);
                jumpCooldown = JUMP_COOLDOWN;
            }
            farmClickTick(client);
            lastX = client.player.getX(); lastZ = client.player.getZ(); lastY = client.player.getY();
            return;
        }
        if (jumpCooldown > 0) jumpCooldown--;

        if (postFlipPauseTicks > 0) {
            postFlipPauseTicks--;
            pressKey(GLFW.GLFW_KEY_A, false);
            pressKey(GLFW.GLFW_KEY_D, false);
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
            pressKey(GLFW.GLFW_KEY_A, false);
            pressKey(GLFW.GLFW_KEY_D, false);
            if (lastY - currentY <= 0.5 && jumpCooldown <= 0) {
                jumpHoldTicks = JUMP_HOLD;
                pressKey(GLFW.GLFW_KEY_SPACE, true);
            }
            goingRight = !goingRight;
            rowCount++;
            postFlipPauseTicks = 5;
            lastX = currentX; lastZ = currentZ; lastY = currentY;
            return;
        }

        if (goingRight) { pressKey(GLFW.GLFW_KEY_A, false); pressKey(GLFW.GLFW_KEY_D, true); }
        else { pressKey(GLFW.GLFW_KEY_D, false); pressKey(GLFW.GLFW_KEY_A, true); }

        farmClickTick(client);
        lastX = currentX; lastZ = currentZ; lastY = currentY;
    }

    private void farmClickTick(MinecraftClient client) {
        clickTickTimer++;
        if (clickTickTimer >= currentClickEvery) {
            clickTickTimer = 0;
            float range = clickSpeedMax - clickSpeedMin;
            float speed = clickSpeedMin + random.nextFloat() * range;
            currentClickEvery = Math.max(1, (int)(speed / 0.05f));
            if (client.crosshairTarget != null &&
                client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                client.interactionManager.attackBlock(
                    ((BlockHitResult) client.crosshairTarget).getBlockPos(),
                    ((BlockHitResult) client.crosshairTarget).getSide()
                );
                client.player.swingHand(Hand.MAIN_HAND);
                clickCount++;
            }
        }
    }

    // ── Slay tick ─────────────────────────────────────────────────────────────
    private void tickSlay(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        Vec3d playerPos = client.player.getPos();

        // Find nearest mob within 15 blocks
        List<LivingEntity> nearbyMobs = client.world.getEntitiesByClass(
            LivingEntity.class,
            new Box(playerPos.x - 15, playerPos.y - 5, playerPos.z - 15,
                    playerPos.x + 15, playerPos.y + 5, playerPos.z + 15),
            e -> e instanceof MobEntity && !e.isDead() && e != client.player
        );

        // Sort by distance
        nearbyMobs.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(client.player)));

        if (nearbyMobs.isEmpty()) {
            // No mobs — spin slowly and wait
            aimState = "scanning";
            stopAllMovement();
            float newYaw = client.player.getYaw() + spinSpeed;
            client.player.setYaw(newYaw);
            return;
        }

        LivingEntity target = nearbyMobs.get(0);
        double dist = Math.sqrt(target.squaredDistanceTo(client.player));

        // ── Aim at target ─────────────────────────────────────────────────────
        double dx = target.getX() - client.player.getX();
        double dy = (target.getY() + target.getStandingEyeHeight() / 2) - (client.player.getY() + client.player.getStandingEyeHeight());
        double dz = target.getZ() - client.player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float)(Math.toDegrees(Math.atan2(-dx, dz)));
        float targetPitch = (float)(-Math.toDegrees(Math.atan2(dy, horizontalDist)));

        // Add subtle random wobble to look human
        wobbleTimer++;
        if (wobbleTimer >= 10) {
            wobbleTimer = 0;
            yawWobble = (random.nextFloat() - 0.5f) * 3f;
        }

        // Smooth aim (lerp toward target)
        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();
        float newYaw = lerpAngle(currentYaw, targetYaw + yawWobble, 0.3f);
        float newPitch = lerp(currentPitch, targetPitch, 0.3f);

        client.player.setYaw(newYaw);
        client.player.setPitch(Math.max(-90f, Math.min(90f, newPitch)));

        // ── Movement based on distance ────────────────────────────────────────
        stopAllMovement();

        if (dist < minDistance) {
            // Too close — back up + strafe
            aimState = "too close — backing";
            pressKey(GLFW.GLFW_KEY_S, true);
            pressKey(strafeDir == 1 ? GLFW.GLFW_KEY_D : GLFW.GLFW_KEY_A, true);
        } else if (dist <= maxDistance) {
            // Sweet spot — just strafe
            aimState = "kiting";
            pressKey(strafeDir == 1 ? GLFW.GLFW_KEY_D : GLFW.GLFW_KEY_A, true);
        } else {
            // Too far — stand still
            aimState = "holding — target far";
        }

        // Switch strafe direction randomly every 40-80 ticks
        strafeSwitchTimer++;
        if (strafeSwitchTimer >= 40 + random.nextInt(40)) {
            strafeSwitchTimer = 0;
            strafeDir *= -1;
        }

        // ── Auto fire ─────────────────────────────────────────────────────────
        slayClickTimer++;
        if (slayClickTimer >= slayClickEvery) {
            slayClickTimer = 0;
            // Randomize CPS
            float cps = slayCpsMin + random.nextFloat() * (slayCpsMax - slayCpsMin);
            slayClickEvery = Math.max(1, (int)(20f / cps));

            // Right click to fire crossbow
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            client.player.swingHand(Hand.MAIN_HAND);
            clickCount++;

            // Count kill if target dies shortly after
            if (target.getHealth() <= 0) slayKills++;
        }
    }

    // ── Lerp helpers ──────────────────────────────────────────────────────────
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float lerpAngle(float a, float b, float t) {
        float diff = ((b - a) % 360 + 540) % 360 - 180;
        return a + diff * t;
    }

    // ── Backpack check ────────────────────────────────────────────────────────
    private void checkBackpackFull() {
        if (backpackMax <= 0) return;
        int pct = (backpackCurrent * 100) / backpackMax;
        if (pct >= backpackAlertPercent && !backpackAlertSent) {
            backpackAlertSent = true;
            sendWebhook(
                "🎒 **Backpack " + pct + "% Full!**\n" +
                "👤 Player: **" + minecraftUsername + "**\n" +
                "📦 " + backpackCurrent + "/" + backpackMax + " capacity used\n" +
                "⚔️ Kills this session: **" + slayKills + "**\n" +
                "⏱ Session: **" + formatTime((System.currentTimeMillis() - startTime) / 1000) + "**"
            );
        }
        if (pct < backpackAlertPercent - 5) backpackAlertSent = false;
    }

    // ── Start / Stop ──────────────────────────────────────────────────────────
    private void startBot(MinecraftClient client) {
        startTime = System.currentTimeMillis();
        clickCount = 0; activityCheckCount = 0;
        wasHandledScreenOpen = false;
        backpackAlertSent = false;
        waitingForBalBefore = true;
        balCheckDelay = 20;

        if (currentMode == BotMode.FARM) {
            goingRight = true; rowCount = 0;
            stuckTicks = 0; jumpHoldTicks = 0; jumpCooldown = 0;
            postFlipPauseTicks = 0;
            lastX = client.player.getX();
            lastZ = client.player.getZ();
            lastY = client.player.getY();
            float range = clickSpeedMax - clickSpeedMin;
            float speed = clickSpeedMin + random.nextFloat() * range;
            currentClickEvery = Math.max(1, (int)(speed / 0.05f));
        } else if (currentMode == BotMode.SLAY) {
            slayKills = 0; slayClickTimer = 0;
            strafeDir = 1; strafeSwitchTimer = 0;
            aimState = "scanning";
        }

        String modeStr = currentMode == BotMode.FARM ? "🌾 Farming" : "⚔️ Slaying";
        client.player.sendMessage(
            Text.literal("§a[BotMaster] " + modeStr + " started! §7H§f=stop §7J§f=config"), true);

        if (notifyBotStart && !webhookUrl.isEmpty()) {
            sendWebhook(
                (currentMode == BotMode.FARM ? "🌾" : "⚔️") + " **BotMaster Started — " +
                currentMode.name() + "**\n" +
                "👤 Player: **" + minecraftUsername + "**\n" +
                "⏱ Session limit: **" +
                (sessionLimitMinutes > 0 ? sessionLimitMinutes + " min" : "Unlimited") + "**"
            );
        }
    }

    private void stopBot(MinecraftClient client) {
        stopAllMovement();
        pressKey(GLFW.GLFW_KEY_SPACE, false);
        jumpHoldTicks = 0;
        waitingForBalAfter = true;
        balCheckDelay = 20;
        client.player.sendMessage(Text.literal("§c[BotMaster] Stopped."), true);
    }

    private void onBalanceAfterReceived() {
        long profit = balanceAfter - balanceBefore;
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        String duration = formatTime(elapsed);
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        long perMin = elapsed > 0 ? (profit * 60) / elapsed : 0;

        sessionHistory.add(0, new SessionRecord(time, duration, currentMode.name(), profit, slayKills));
        if (sessionHistory.size() > 10) sessionHistory.remove(sessionHistory.size() - 1);

        if (notifySessionEnd && !webhookUrl.isEmpty()) {
            String modeEmoji = currentMode == BotMode.FARM ? "🌾" : "⚔️";
            String extra = currentMode == BotMode.FARM
                ? "🌾 Rows: **" + rowCount + "** | Clicks: **" + clickCount + "**\n"
                : "⚔️ Kills: **" + slayKills + "** | Clicks: **" + clickCount + "**\n";
            sendWebhook(
                modeEmoji + " **BotMaster Session Complete — " + currentMode.name() + "**\n" +
                "👤 Player: **" + minecraftUsername + "**\n" +
                "⏱ Duration: **" + duration + "**\n" +
                extra +
                "\n💰 Balance Before: **$" + formatMoney(balanceBefore) + "**\n" +
                "💰 Balance After:  **$" + formatMoney(balanceAfter) + "**\n" +
                "📈 Profit Made:    **+$" + formatMoney(profit) + "**\n" +
                "⚡ Per Minute:     **$" + formatMoney(perMin) + "/min**"
            );
        }
    }

    // ── HUD ───────────────────────────────────────────────────────────────────
    private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        if (!showGui) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int x = 10, y = 10, w = 250;
        boolean isFarm = currentMode == BotMode.FARM;
        boolean isSlay = currentMode == BotMode.SLAY;
        int h = botActive ? (isSlay ? 210 : 195) : 55;

        context.fill(x, y, x + w, y + h, 0xCC0a0a1a);
        context.fill(x, y, x + w, y + 2, isFarm ? 0xFF00ff88 : isSlay ? 0xFFff4444 : 0xFF6666dd);
        context.drawBorder(x, y, w, h, isFarm ? 0xFF00ff44 : isSlay ? 0xFFaa2222 : 0xFF444488);

        String modeTag = currentMode == BotMode.NONE ? "§7No job selected" :
                         isFarm ? "§a🌾 Farming" : "§c⚔️ Slaying";
        context.drawText(client.textRenderer,
            Text.literal("§fBotMaster §8| " + modeTag + " §8| §eJ§f=menu"),
            x + 6, y + 6, 0xFFFFFF, false);

        String status = !botActive ? "§c● STOPPED" :
                        isSlay && jumpHoldTicks > 0 ? "§b● JUMPING" : "§a● RUNNING";
        context.drawText(client.textRenderer,
            Text.literal(status), x + 6, y + 18, 0xFFFFFF, false);

        if (botActive) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            context.drawText(client.textRenderer,
                Text.literal(String.format("§7Time: §f%s%s", formatTime(elapsed),
                    sessionLimitMinutes > 0 ? " §7/§e " + sessionLimitMinutes + "min" : "")),
                x + 6, y + 31, 0xFFFFFF, false);

            if (isFarm) {
                context.drawText(client.textRenderer,
                    Text.literal("§7Rows: §f" + rowCount + "  §7Clicks: §f" + clickCount),
                    x + 6, y + 41, 0xFFFFFF, false);
                context.drawText(client.textRenderer,
                    Text.literal("§7Dir: §f" + (goingRight ? "→R" : "←L") +
                        "  §7Jump: §f" + (jumpHoldTicks > 0 ? "§bHOLD" : jumpCooldown > 0 ? "§7cd" : "§aOK")),
                    x + 6, y + 51, 0xFFFFFF, false);
                context.fill(x + 4, y + 63, x + w - 4, y + 64, 0xFF333355);
                if (balanceCurrent > 0) {
                    long profit = balanceCurrent - balanceBefore;
                    context.drawText(client.textRenderer,
                        Text.literal("§7Bal: §e$" + formatMoney(balanceCurrent) +
                            (profit > 0 ? "  §a(+" + formatMoney(profit) + ")" : "")),
                        x + 6, y + 67, 0xFFFFFF, false);
                }
                context.drawText(client.textRenderer,
                    Text.literal(String.format("§7X:§f%.0f §7Z:§f%.0f §7Y:§f%.0f",
                        client.player.getX(), client.player.getZ(), client.player.getY())),
                    x + 6, y + 78, 0xFFFFFF, false);
                context.drawText(client.textRenderer,
                    Text.literal("§7Checks: §e" + activityCheckCount +
                        "  §7Webhook: " + (webhookUrl.isEmpty() ? "§cNot set" : "§aSet ✔")),
                    x + 6, y + 88, 0xFFFFFF, false);
                if (!sessionHistory.isEmpty()) {
                    context.fill(x + 4, y + 100, x + w - 4, y + 101, 0xFF333355);
                    SessionRecord last = sessionHistory.get(0);
                    context.drawText(client.textRenderer,
                        Text.literal("§7Last: §a+$" + formatMoney(last.profit) + " §7in §f" + last.duration),
                        x + 6, y + 104, 0xFFFFFF, false);
                }

            } else if (isSlay) {
                context.drawText(client.textRenderer,
                    Text.literal("§7Kills: §f" + slayKills + "  §7Clicks: §f" + clickCount),
                    x + 6, y + 41, 0xFFFFFF, false);
                long secs = elapsed > 0 ? elapsed : 1;
                float kpm = (slayKills * 60f) / secs;
                context.drawText(client.textRenderer,
                    Text.literal(String.format("§7Kills/min: §f%.1f", kpm)),
                    x + 6, y + 51, 0xFFFFFF, false);
                context.drawText(client.textRenderer,
                    Text.literal("§7Aim: §f" + aimState),
                    x + 6, y + 61, 0xFFFFFF, false);
                context.drawText(client.textRenderer,
                    Text.literal(String.format("§7Yaw: §f%.1f  §7Pitch: §f%.1f",
                        client.player.getYaw(), client.player.getPitch())),
                    x + 6, y + 71, 0xFFFFFF, false);
                context.fill(x + 4, y + 83, x + w - 4, y + 84, 0xFF333355);
                if (backpackMax > 0) {
                    int pct = (backpackCurrent * 100) / backpackMax;
                    String bpColor = pct >= backpackAlertPercent ? "§c" : pct >= 70 ? "§e" : "§a";
                    context.drawText(client.textRenderer,
                        Text.literal("§7Backpack: " + bpColor + backpackCurrent + "/" + backpackMax +
                            " (" + pct + "%)"),
                        x + 6, y + 87, 0xFFFFFF, false);
                    // Backpack bar
                    int barW = w - 12;
                    int filled = (barW * pct) / 100;
                    context.fill(x + 6, y + 99, x + 6 + barW, y + 105, 0xFF222233);
                    int barColor = pct >= backpackAlertPercent ? 0xFFff4444 : pct >= 70 ? 0xFFffcc44 : 0xFF44ff88;
                    context.fill(x + 6, y + 99, x + 6 + filled, y + 105, barColor);
                } else {
                    context.drawText(client.textRenderer,
                        Text.literal("§7Backpack: §8waiting for data..."),
                        x + 6, y + 87, 0xFFFFFF, false);
                }
                context.fill(x + 4, y + 108, x + w - 4, y + 109, 0xFF333355);
                context.drawText(client.textRenderer,
                    Text.literal(String.format("§7X:§f%.0f §7Z:§f%.0f §7Y:§f%.0f",
                        client.player.getX(), client.player.getZ(), client.player.getY())),
                    x + 6, y + 112, 0xFFFFFF, false);
                context.drawText(client.textRenderer,
                    Text.literal("§7Checks: §e" + activityCheckCount +
                        "  §7Webhook: " + (webhookUrl.isEmpty() ? "§cNot set" : "§aSet ✔")),
                    x + 6, y + 122, 0xFFFFFF, false);
            }
        }
    }

    // ── Main Screen ───────────────────────────────────────────────────────────
    public static class MainScreen extends Screen {
        private final Screen parent;
        private int view = 0; // 0=job select, 1=config, 2=stats

        public MainScreen(Screen parent) {
            super(Text.literal("BotMaster"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int cx = width / 2, cy = height / 2;
            int pw = 320, ph = 360;
            int px = cx - pw/2, py = cy - ph/2;

            // Nav buttons
            addDrawableChild(ButtonWidget.builder(Text.literal("Job"), btn -> view = 0)
                .dimensions(px, py + 28, 107, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Config"), btn -> view = 1)
                .dimensions(px + 107, py + 28, 106, 18).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Stats"), btn -> view = 2)
                .dimensions(px + 213, py + 28, 107, 18).build());

            // Job select buttons
            addDrawableChild(ButtonWidget.builder(Text.literal("🌾 Farming"), btn -> {
                currentMode = BotMode.FARM;
                view = 0;
            }).dimensions(px + 10, py + 90, 140, 28).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("⚔️ Slaying"), btn -> {
                currentMode = BotMode.SLAY;
                view = 0;
            }).dimensions(px + 170, py + 90, 140, 28).build());

            // Start/Stop button
            addDrawableChild(ButtonWidget.builder(
                Text.literal(botActive ? "⏹ Stop  [H]" : "▶ Start  [H]"),
                btn -> {
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.player != null) {
                        botActive = !botActive;
                        if (botActive) new FarmBotMod().startBot(client);
                        else new FarmBotMod().stopBot(client);
                    }
                }).dimensions(px + pw/2 - 70, py + 130, 140, 22).build());

            // Close
            addDrawableChild(ButtonWidget.builder(Text.literal("Close"), btn -> close())
                .dimensions(px + pw/2 - 40, py + ph - 26, 80, 18).build());
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            int cx = width / 2, cy = height / 2;
            int pw = 320, ph = 360;
            int px = cx - pw/2, py = cy - ph/2;

            ctx.fill(px, py, px + pw, py + ph, 0xEE0a0a1a);
            ctx.fill(px, py, px + pw, py + 3,
                currentMode == BotMode.FARM ? 0xFF00ff88 :
                currentMode == BotMode.SLAY ? 0xFFff4444 : 0xFF6666dd);
            ctx.drawBorder(px, py, pw, ph,
                currentMode == BotMode.FARM ? 0xFF00ff44 :
                currentMode == BotMode.SLAY ? 0xFFaa2222 : 0xFF444488);

            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§fBotMaster"), cx, py + 10, 0xFFFFFF);

            // Tab underline
            int[] tabX = {px, px+107, px+213};
            int[] tabW = {107, 106, 107};
            ctx.fill(tabX[view], py + 46, tabX[view] + tabW[view], py + 48, 0xFF6666dd);

            if (view == 0) renderJobView(ctx, px, py, pw, cx);
            else if (view == 1) renderConfigView(ctx, px, py, pw, cx);
            else renderStatsView(ctx, px, py, pw, cx);

            super.render(ctx, mx, my, delta);
        }

        private void renderJobView(DrawContext ctx, int px, int py, int pw, int cx) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7Select a job to run"), cx, py + 68, 0xAAAAAA);

            // Job cards highlight
            if (currentMode == BotMode.FARM)
                ctx.fill(px + 8, py + 88, px + 152, py + 120, 0xFF0a1a0a);
            if (currentMode == BotMode.SLAY)
                ctx.fill(px + 168, py + 88, px + pw - 8, py + 120, 0xFF1a0a0a);

            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7auto harvest crops"), cx - 75, py + 122, 0x555555);
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7auto kill mobs"), cx + 75, py + 122, 0x555555);

            // Current job + status
            String jobStr = currentMode == BotMode.NONE ? "§8None selected" :
                            currentMode == BotMode.FARM ? "§a🌾 Farming" : "§c⚔️ Slaying";
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7Job: " + jobStr), cx, py + 160, 0xFFFFFF);
            String statusStr = botActive ? "§a● Running" : "§c● Stopped";
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("§7Status: " + statusStr), cx, py + 174, 0xFFFFFF);
        }

        private void renderConfigView(DrawContext ctx, int px, int py, int pw, int cx) {
            // Placeholder — fields added in init
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Config coming in full version"), px + 12, py + 70, 0x888888);
        }

        private void renderStatsView(DrawContext ctx, int px, int py, int pw, int cx) {
            long profit = balanceAfter > 0 ? balanceAfter - balanceBefore : 0;
            int y = py + 60;
            ctx.fill(px + 10, y, px + pw - 10, y + 44, 0xFF0a1a0a);
            ctx.drawBorder(px + 10, y, pw - 20, 44, 0xFF1a4a2a);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7Session Profit"), px + 18, y + 6, 0x44aa44);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§a+$" + formatMoney(Math.max(0, profit))), px + 18, y + 18, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer,
                Text.literal("§7Kills: §f" + slayKills), px + 18, y + 30, 0xFFFFFF);
            y += 52;

            ctx.drawTextWithShadow(textRenderer, Text.literal("§7History"), px + 12, y, 0x4444aa);
            y += 12;
            if (sessionHistory.isEmpty()) {
                ctx.drawTextWithShadow(textRenderer, Text.literal("§8No sessions yet"), px + 12, y, 0xFFFFFF);
            } else {
                for (int i = 0; i < Math.min(sessionHistory.size(), 6); i++) {
                    SessionRecord r = sessionHistory.get(i);
                    ctx.fill(px + 10, y, px + pw - 10, y + 18, 0xFF0a0a1a);
                    ctx.drawBorder(px + 10, y, pw - 20, 18, 0xFF1a1a3a);
                    ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§8" + r.time + " §7[" + r.mode + "]"), px + 14, y + 5, 0xFFFFFF);
                    ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§a+$" + formatMoney(r.profit)), px + 140, y + 5, 0xFFFFFF);
                    ctx.drawTextWithShadow(textRenderer,
                        Text.literal("§7" + r.duration), px + pw - 55, y + 5, 0xFFFFFF);
                    y += 22;
                }
            }
        }

        @Override
        public void close() { client.setScreen(parent); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private static String formatTime(long secs) {
        return String.format("%02d:%02d", secs / 60, secs % 60);
    }

    private static String formatMoney(long amount) {
        return String.format("%,d", amount);
    }

    private static void sendWebhook(String message) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;
        String json = "{\"content\":\"" +
            message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
}
