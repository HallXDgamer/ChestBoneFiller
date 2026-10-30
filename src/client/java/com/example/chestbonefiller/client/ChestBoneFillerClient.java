package com.example.chestbonefiller.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class ChestBoneFillerClient implements ClientModInitializer {

    static final double REACH = 4.5;       // chest kholne ki max doori (blocks)
    static final int BONE_SLOT = 20;       // bone wali slot (mob drops menu)
    static final int BUY_SLOT = 26;        // 36 wali bone (buy more menu)

    static final List<BlockPos> chests = new ArrayList<>();
    static boolean selectorOn = false;

    static int idx = 0;
    static int state = 0;        // 0 = idle
    static int wait = 0;
    static int lastBones = -1;
    static int stuck = 0;
    static int timeouts = 0;
    static boolean justBought = false;

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

        // selector ON ho to chest par right-click = select / unselect
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!selectorOn || level != mc.level) return InteractionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            BlockState bs = level.getBlockState(pos);
            if (!bs.is(Blocks.CHEST) && !bs.is(Blocks.TRAPPED_CHEST)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;

            BlockPos other = null;
            if (bs.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                other = pos.relative(ChestBlock.getConnectedDirection(bs));
            }

            if (chests.contains(pos)) {
                chests.remove(pos);
                msg(mc, "Chest hata diya. Total: " + chests.size());
            } else if (other != null && chests.contains(other)) {
                chests.remove(other);
                msg(mc, "Chest hata diya. Total: " + chests.size());
            } else {
                chests.add(pos);
                msg(mc, "Chest select hua: " + pos.toShortString() + " | Total: " + chests.size());
            }
            return InteractionResult.FAIL;
        });
    }

    void msg(Minecraft mc, String s) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[ChestBoneFiller] " + s), false);
        }
    }

    void tick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null || mc.level == null) return;

        // R: chest selector ON / OFF
        while (selectKey.consumeClick()) {
            if (!selectorOn) {
                chests.clear();
                selectorOn = true;
                msg(mc, "Chest selector ON - chest par right-click karo, dobara R se band karo");
            } else {
                selectorOn = false;
                msg(mc, "Chest selector OFF - " + chests.size() + " chest select hue");
            }
        }

        // H: stop (GUI khula ho tab bhi chalega)
        boolean stopHeld = GLFW.glfwGetKey(GLFW.glfwGetCurrentContext(), GLFW.GLFW_KEY_H) == GLFW.GLFW_PRESS;
        while (stopKey.consumeClick()) { stopHeld = true; }
        if (stopHeld && state != 0) {
            state = 0;
            mc.player.closeContainer();
            msg(mc, "Stopped");
            return;
        }

        // G: start
        while (startKey.consumeClick()) {
            if (selectorOn) {
                selectorOn = false;
                msg(mc, "Chest selector OFF - " + chests.size() + " chest select hue");
            }
            if (chests.isEmpty()) {
                msg(mc, "Pehle chests select karo (R)");
            } else {
                idx = 0; lastBones = -1; stuck = 0; timeouts = 0; wait = 0;
                justBought = false;
                state = 6;   // pehle inventory ke bones chest me daalo
                msg(mc, "Started (" + chests.size() + " chests)");
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
            // 2) Mob Drops / Mod Drops
            case 2 -> {
                if (clickByAnyName(mc, "mob drops", "mod drops", "drops")) { state = 3; wait = 15; }
                else timeout(mc);
            }
            // 3) Bone (slot 20, nahi to naam se)
            case 3 -> {
                if (clickSlotIfBone(mc, BONE_SLOT, false) || clickByAnyName(mc, "bone")) {
                    state = 4; wait = 15;
                } else timeout(mc);
            }
            // 4) Buy More
            case 4 -> {
                if (clickByAnyName(mc, "buy more", "more")) { state = 5; wait = 15; }
                else timeout(mc);
            }
            // 5) 36 wali bone (slot 26)
            case 5 -> {
                if (clickSlotIfBone(mc, BUY_SLOT, true)) {
                    justBought = true;
                    state = 6; wait = 15;
                } else timeout(mc);
            }
            // 6) current chest kholo
            case 6 -> {
                if (idx >= chests.size()) {
                    mc.player.closeContainer();
                    state = 0;
                    msg(mc, "Saare chests full / done");
                    return;
                }
                BlockPos cp = chests.get(idx);

                if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(cp)) > REACH + 0.5) {
                    msg(mc, "Chest door hai, skip: " + cp.toShortString());
                    idx++;
                    wait = 2;
                    return;
                }

                mc.player.closeContainer();
                BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(cp), Direction.UP, cp, false);
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

                // inventory me bones khatam -> shop se kharido
                if (firstBone == -1) {
                    mc.player.closeContainer();
                    if (justBought) {
                        state = 0;
                        msg(mc, "Bones nahi mile (paise ya shop check karo), stopped");
                    } else {
                        state = 1; wait = 10;
                    }
                    return;
                }

                // chest full check -> agle chest par jao
                if (bones == lastBones) stuck++; else stuck = 0;
                lastBones = bones;
                if (stuck >= 5) {
                    idx++;
                    state = 6; wait = 5;
                    return;
                }

                justBought = false;
                mc.gameMode.handleInventoryMouseClick(h.containerId, firstBone, 0,
                    ClickType.QUICK_MOVE, mc.player);
                wait = 3;
            }
        }
    }

    // ~5 sec tak step na ho paye to ruk jao aur screen ke items chat me dikhao
    void timeout(Minecraft mc) {
        if (++timeouts > 100) {
            timeouts = 0;
            int failedStep = state;
            state = 0;
            msg(mc, "Step " + failedStep + " par ruk gaya (2=Drops, 3=Bone, 4=Buy More, 5=slot 26, 7=chest)");
            if (mc.screen instanceof AbstractContainerScreen<?> s) {
                AbstractContainerMenu h = s.getMenu();
                int top = h.slots.size() - 36;
                for (int i = 0; i < top; i++) {
                    Slot slot = h.slots.get(i);
                    if (!slot.getItem().isEmpty()) {
                        msg(mc, i + " : " + slot.getItem().getHoverName().getString());
                    }
                }
            } else {
                msg(mc, "Koi GUI khula hi nahi tha");
            }
        }
    }

    // diye hue slot par bone ho to click karo
    boolean clickSlotIfBone(Minecraft mc, int slotIndex, boolean mustBeInTopGui) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> s)) return false;
        AbstractContainerMenu h = s.getMenu();
        if (h.slots.size() <= slotIndex) return false;
        if (!h.slots.get(slotIndex).getItem().is(Items.BONE)) return false;
        mc.gameMode.handleInventoryMouseClick(h.containerId, slotIndex, 0, ClickType.PICKUP, mc.player);
        timeouts = 0;
        return true;
    }

    // upar ki GUI me naam se item dhundo (pehla naam jo mile). Naam ke kisi bhi hisse se match.
    boolean clickByAnyName(Minecraft mc, String... names) {
        if (!(mc.screen instanceof AbstractContainerScreen<?> s)) return false;
        AbstractContainerMenu h = s.getMenu();
        int top = h.slots.size() - 36;
        for (String name : names) {
            for (int i = 0; i < top; i++) {
                Slot slot = h.slots.get(i);
                if (slot.getItem().isEmpty()) continue;
                String n = slot.getItem().getHoverName().getString().toLowerCase();
                if (n.contains(name.toLowerCase())) {
                    mc.gameMode.handleInventoryMouseClick(h.containerId, slot.index, 0,
                        ClickType.PICKUP, mc.player);
                    timeouts = 0;
                    return true;
                }
            }
        }
        return false;
    }
}
