package com.example.bonemacro;

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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class BoneMacro implements ClientModInitializer {

    // true = buy -> chest -> buy ... loop me chalega, false = sirf ek baar
    static final boolean LOOP = false;

    static BlockPos chestPos = null;
    static int state = 0;      // 0 = idle
    static int wait = 0;       // ticks ka delay (20 = 1 sec)
    static int lastBones = -1; // chest full detect karne ke liye
    static int stuck = 0;

    KeyBinding selectKey, startKey, stopKey;

    @Override
    public void onInitializeClient() {
        selectKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Select chest", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "BoneMacro"));
        startKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Start macro", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "BoneMacro"));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "Stop macro", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, "BoneMacro"));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    void msg(MinecraftClient mc, String s) {
        mc.player.sendMessage(Text.literal("[BoneMacro] " + s), false);
    }

    void tick(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null) return;

        // ---- chest select (chest ko dekh ke R dabao) ----
        while (selectKey.wasPressed()) {
            if (mc.crosshairTarget instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK) {
                chestPos = hit.getBlockPos();
                msg(mc, "Chest selected: " + chestPos.toShortString());
            } else {
                msg(mc, "Chest ko dekh ke dabao");
            }
        }

        // ---- stop ----
        while (stopKey.wasPressed()) {
            state = 0;
            msg(mc, "Stopped");
        }

        // ---- start ----
        while (startKey.wasPressed()) {
            if (chestPos == null) {
                msg(mc, "Pehle chest select karo (R)");
            } else {
                state = 1; wait = 0; lastBones = -1; stuck = 0;
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
                    state = 6; wait = 15;
                } else timeout(mc);
            }
            // 6) selected chest kholo
            case 6 -> {
                mc.player.closeHandledScreen();
                BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(chestPos), Direction.UP, chestPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                state = 7; wait = 10; lastBones = -1; stuck = 0;
            }
            // 7) bones chest me shift-click
            case 7 -> {
                if (!(mc.currentScreen instanceof HandledScreen<?> s)) { timeout(mc); return; }
                ScreenHandler h = s.getScreenHandler();
                int chestSize = h.slots.size() - 36;
                if (chestSize <= 0) { timeout(mc); return; }

                int bones = 0, firstBone = -1;
                for (int i = chestSize; i < h.slots.size(); i++) {
                    if (h.slots.get(i).getStack().isOf(Items.BONE)) {
                        bones += h.slots.get(i).getStack().getCount();
                        if (firstBone == -1) firstBone = i;
                    }
                }

                if (firstBone == -1) {               // inventory me bones khatam
                    mc.player.closeHandledScreen();
                    if (LOOP) { state = 1; wait = 20; }
                    else { state = 0; msg(mc, "Done"); }
                    return;
                }

                // chest full check: bones kam nahi hue to rukk jao
                if (bones == lastBones) stuck++; else stuck = 0;
                lastBones = bones;
                if (stuck >= 5) {
                    mc.player.closeHandledScreen();
                    state = 0;
                    msg(mc, "Chest full, stopped");
                    return;
                }

                mc.interactionManager.clickSlot(h.syncId, firstBone, 0,
                    SlotActionType.QUICK_MOVE, mc.player);
                wait = 3;
            }
        }
    }

    int timeouts = 0;
    void timeout(MinecraftClient mc) {
        if (++timeouts > 100) {   // ~5 sec tak GUI nahi mila
            timeouts = 0;
            state = 0;
            msg(mc, "GUI nahi mila, stopped (slot ka naam check karo)");
        }
    }

    boolean clickByName(MinecraftClient mc, String name) {
        if (!(mc.currentScreen instanceof HandledScreen<?> s)) return false;
        ScreenHandler h = s.getScreenHandler();
        for (Slot slot : h.slots) {
            if (slot.getStack().isEmpty()) continue;
            String n = slot.getStack().getName().getString().toLowerCase();
            if (n.contains(name.toLowerCase())) {
                mc.interactionManager.clickSlot(h.syncId, slot.id, 0,
                    SlotActionType.PICKUP, mc.player);
                timeouts = 0;
                return true;
            }
        }
        return false;
    }
}
