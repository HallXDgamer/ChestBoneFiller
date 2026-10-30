package com.example.chestbonefiller.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class ChestBoneFillerClient implements ClientModInitializer {

    // true = buy -> chest -> buy ... loop, false = sirf ek baar
    static final boolean LOOP = false;

    static BlockPos chestPos = null;
    static int state = 0;       // 0 = idle
    static int wait = 0;        // ticks (20 = 1 sec)
    static int lastBones = -1;
    static int stuck = 0;
    static int timeouts = 0;

    KeyMapping selectKey, startKey, stopKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("chestbonefiller", "main"));

        selectKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.chestbonefiller.select", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
        startKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.chestbonefiller.start", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.chestbonefiller.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, category));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    void msg(Minecraft mc, String s) {
        mc.player.displayClientMessage(Component.literal("[ChestBoneFiller] " + s), false);
    }

    void tick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) return;

        // chest select: chest ko dekh ke R
        while (selectKey.consumeClick()) {
            if (mc.hitResult instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK) {
                chestPos = hit.getBlockPos();
                msg(mc, "Chest selected: " + chestPos.toShortString());
            } else {
                msg(mc, "Chest ko dekh ke dabao");
            }
        }

        // stop: H
        while (stopKey.consumeClick()) {
            state = 0;
            msg(mc, "Stopped");
        }

        // start: G
        while (startKey.consumeClick()) {
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
                mc.player.connection.sendCommand("shop");
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
                if (!(mc.screen instanceof AbstractContainerScreen<?> s)) { timeout(mc); return; }
                AbstractContainerMenu h = s.getMenu();
                if (h.slots.size() <= 26) { timeout(mc); return; }
                Slot slot = h.slots.get(26);
                if (slot.getItem().is(Items.BONE)) {
                    mc.gameMode.handleInventoryMouseClick(h.containerId, 26, 0, ClickType.PICKUP, mc.player);
                    timeouts = 0;
                    state = 6; wait = 15;
                } else timeout(mc);
            }
            // 6) selected chest kholo
            case 6 -> {
                mc.player.closeContainer();
                BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(chestPos), Direction.UP, chestPos, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                state = 7; wait = 10; lastBones = -1; stuck = 0; timeouts = 0;
            }
            // 7) bones chest me shift-click
            case 7 -> {
                if (!(mc.screen instanceof AbstractContainerScreen<?> s)) { timeout(mc); return; }
                AbstractContainerMenu h = s.getMenu();
                int chestSize = h.slots.size() - 36;
                if (chestSize <= 0) { timeout(mc); return; }
                timeouts = 0;

                int bones = 0;
                int firstBone = -1;
                for (int i = chestSize; i < h.slots.size(); i++) {
                    if (h.slots.get(i).getItem().is(Items.BONE)) {
                        bones += h.slots.get(i).getItem().getCount();
                        if (firstBone == -1) firstBone = i;
                    }
                }

                // inventory me bones khatam
                if (firstBone == -1) {
                    mc.player.closeContainer();
                    if (LOOP) { state = 1; wait = 20; }
                    else { state = 0; msg(mc, "Done"); }
                    return;
                }

                // chest full check
                if (bones == lastBones) stuck++; else stuck = 0;
                lastBones = bones;
                if (stuck >= 5) {
                    mc.player.closeContainer();
                    state = 0;
                    msg(mc, "Chest full, stopped");
                    return;
                }

                mc.gameMode.handleInventoryMouseClick(h.containerId, firstBone, 0,
                    ClickType.QUICK_MOVE, mc.player);
                wait = 3;
            }
        }
    }

    void timeout(Minecraft mc) {
        if (++timeouts > 100) {   // ~5 sec tak GUI nahi mila
            timeouts = 0;
            state = 0;
            msg(mc, "GUI nahi mila, stopped (slot ka naam check karo)");
        }
    }

    boolean clickByName(Minecraft mc, String name) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> s)) return false;
        AbstractContainerMenu h = s.getMenu();
        for (Slot slot : h.slots) {
            if (slot.getItem().isEmpty()) continue;
            String n = slot.getItem().getHoverName().getString().toLowerCase();
            if (n.contains(name.toLowerCase())) {
                mc.gameMode.handleInventoryMouseClick(h.containerId, slot.index, 0,
                    ClickType.PICKUP, mc.player);
                timeouts = 0;
                return true;
            }
        }
        return false;
    }
}
