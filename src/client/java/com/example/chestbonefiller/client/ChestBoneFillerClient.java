package com.example.chesbonefiller;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class BoneMacro implements ClientModInitializer {

    // true = buy -> chest -> buy ... loop, false = sirf ek baar
    static final boolean LOOP = false;

    static BlockPos chestPos = null;
    static int state = 0;       // 0 = idle
    static int wait = 0;        // ticks (20 = 1 sec)
    static int lastBones = -1;
    static int stuck = 0;
    static int timeouts = 0;

    KeyBinding selectKey, startKey, stopKey;

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category =
            KeyBinding.Category.create(Identifier.of("chesbonefiller", "main"));

        selectKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chesbonefiller.select", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
        startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chesbonefiller.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.chesbonefiller.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    void msg(MinecraftClient mc, String s) {
        mc.player.sendMessage(Text.literal("[ChesBoneFiller] " + s), false);
    }

    void tick(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null) return;

        // chest select: chest ko dekh ke R
        while (selectKey.wasPressed()) {
            if (mc.crosshairTarget instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK) {
                chestPos = hit.getBlockPos();
                msg(mc, "Chest selected: " + chestPos.toShortString());
            } else {
                msg(mc, "Chest ko dekh ke dabao");
            }
        }

        // stop: H
        while (stopKey.wasPressed()) {
            state = 0;
            msg(mc, "Stopped");
        }

        // start: G
        while (startKey.wasPressed()) {
            if (chestPos == null) {
                msg(mc, "Pehle chest select karo (R)");
            } else {
                state = 1; wait = 0; lastBones = -1; stuck = 0; timeouts = 0;
                msg(mc, "Started");
            }
        }

        if (state == 0) return;
        if (wait > 0) { wait--; return; }

        switch (state) {
            // 1) /shop
            case 1 -> {
                mc.player.networkHandler.sendChatCommand("shop");
                state = 2; wait = 20;
            }
            // 2) Mod Drops
            case 2 -> { if (clickByName(mc, "Mod Drops")) { state = 3; wait = 15; } else timeout(mc); }
            // 3) Bone
            case 3 -> { if (clickByName(mc, "Bone"))      { state = 4; wait = 15; } else timeout(mc); }
            // 4) Buy More
            case 4 -> { if (clickByName(mc, "Buy More"))  { state = 5; wait = 15; } else timeout(mc); }
            // 5) 36 wali bone (slot 26)
            case 5 -> {
                if (!(mc.currentScreen instanceof HandledScreen<?> s)) { timeout(mc); return; }
                ScreenHandler h = s.getScreenHandler();
                if (h.slots.size() <= 26) { timeout(mc); return; }
                Slot slot = h.slots.get(26);
                if (slot.getStack().isOf(Items.BONE)) {
                    mc.interactionManager.clickSlot(h.syncId, 26, 0, SlotActionType.PICKUP, mc.player);
                    timeouts = 0;
                    state = 6; wait = 15;
                } else timeout(mc);
            }
            // 6) selected chest kholo
            case 6 -> {
                mc.player.closeHandledScreen();
                BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                state = 7; wait = 10; lastBones = -1; stuck = 0; timeouts = 0;
            }
            // 7) bones chest me shift-click
            case 7 -> {
                if (!(mc.currentScreen instanceof HandledScreen<?> s)) { timeout(mc); return; }
                ScreenHandler h = s.getScreenHandler();
                int
