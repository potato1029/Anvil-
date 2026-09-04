package com.mahdi.anvilplus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Anvil Plus - Minecraft 1.21.11
 *
 * Client-side automation for Netherite Boots only.
 *
 * The mod:
 * - Only works with Netherite Boots.
 * - Finds boots in both the hotbar and main inventory.
 * - Finds enchanted books in the inventory.
 * - Supports books containing multiple enchantments.
 * - Only considers:
 *   Unbreaking III
 *   Feather Falling IV
 *   Blast Protection IV
 *   Thorns III
 *   Depth Strider III
 *   Mending
 *   Soul Speed III
 *
 * The actual Anvil result is still calculated by Minecraft/server.
 */
public class AnvilPlusClient implements ClientModInitializer {

    private static final String MOD_ID = "anvil_plus";

    // 6 ticks = 300 ms.
    private static final int ACTION_DELAY_TICKS = 6;

    // Wait a little after opening the Anvil.
    private static final int OPEN_DELAY_TICKS = 8;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(
                    Identifier.of(MOD_ID, "controls")
            );

    private static KeyBinding toggleKey;

    /**
     * Saved between game launches.
     */
    private static boolean enabled = true;

    private static AnvilScreenHandler lastHandler;
    private static int delay;

    private static Stage stage = Stage.IDLE;

    private static ItemStack selectedBoot = ItemStack.EMPTY;
    private static ItemStack selectedBook = ItemStack.EMPTY;

    private enum Stage {
        IDLE,
        WAITING_FOR_BOOT,
        WAITING_FOR_BOOK,
        WAITING_FOR_OUTPUT,
        WAITING_FOR_CLEAR
    }

    @Override
    public void onInitializeClient() {

        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.anvil_plus.toggle",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_P,
                        CATEGORY
                )
        );

        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(
                AnvilPlusClient::tick
        );
    }

    private static void tick(MinecraftClient client) {

        /*
         * Toggle ON/OFF.
         */
        while (toggleKey.wasPressed()) {

            enabled = !enabled;

            resetState();
            saveConfig();

            if (enabled) {
                actionBar(
                        client,
                        "Anvil Plus: ON",
                        0x55FF55
                );
            } else {
                actionBar(
                        client,
                        "Anvil Plus: OFF",
                        0xFF5555
                );
            }
        }

        if (!enabled
                || client.player == null
                || client.interactionManager == null) {
            return;
        }

        /*
         * The mod only works while an Anvil is actually open.
         */
        if (!(client.currentScreen instanceof AnvilScreen screen)) {
            resetState();
            return;
        }

        AnvilScreenHandler handler = screen.getScreenHandler();

        /*
         * New Anvil screen.
         */
        if (handler != lastHandler) {

            lastHandler = handler;

            delay = OPEN_DELAY_TICKS;

            stage = Stage.IDLE;

            selectedBoot = ItemStack.EMPTY;
            selectedBook = ItemStack.EMPTY;
        }

        if (delay > 0) {
            delay--;
            return;
        }

        switch (stage) {

            case IDLE ->
                    startNext(client, handler);

            case WAITING_FOR_BOOT ->
                    putBook(client, handler);

            case WAITING_FOR_BOOK ->
                    waitForOutput(client, handler);

            case WAITING_FOR_OUTPUT ->
                    takeOutput(client, handler);

            case WAITING_FOR_CLEAR ->
                    waitForClear(handler);
        }
    }

    /*
     * Find the next Netherite Boots and a useful book.
     */
    private static void startNext(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        /*
         * Do not touch an Anvil that already contains items.
         */
        if (!isInputEmpty(handler)) {

            stopWithError(
                    client,
                    "Anvil Plus: Anvil slots are not empty",
                    0xFF5555
            );

            return;
        }

        /*
         * Find Netherite Boots anywhere in the player's inventory.
         */
        int bootSlot = findNextBoot(client);

        if (bootSlot < 0) {

            actionBar(
                    client,
                    "Anvil Plus: No Netherite Boots left",
                    0x55FF55
            );

            delay = 20;

            return;
        }

        ItemStack boot =
                client.player.getInventory().getStack(bootSlot);

        /*
         * Find a book that actually adds at least one useful enchantment.
         */
        int bookSlot =
                findUsefulBook(client, boot);

        if (bookSlot < 0) {

            actionBar(
                    client,
                    "Anvil Plus: No useful book for this boot",
                    0xFFAA00
            );

            delay = 20;

            return;
        }

        selectedBoot =
                boot.copy();

        selectedBook =
                client.player.getInventory()
                        .getStack(bookSlot)
                        .copy();

        /*
         * Move the Netherite Boots into Anvil slot 1.
         */
        clickInventorySlot(
                client,
                handler,
                bootSlot
        );

        stage =
                Stage.WAITING_FOR_BOOT;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * Wait until the boots are actually inside the Anvil,
     * then put the selected book into slot 2.
     */
    private static void putBook(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        ItemStack input =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack();

        if (input.isEmpty()) {

            delay = 1;

            return;
        }

        /*
         * Find the exact same book again.
         */
        int bookSlot =
                findExactBook(
                        client,
                        selectedBook
                );

        if (bookSlot < 0) {

            stopWithError(
                    client,
                    "Anvil Plus: Enchanted Book not found",
                    0xFF5555
            );

            return;
        }

        /*
         * Move the book into Anvil slot 2.
         */
        clickInventorySlot(
                client,
                handler,
                bookSlot
        );

        stage =
                Stage.WAITING_FOR_BOOK;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * Wait until both Anvil input slots are synchronized.
     */
    private static void waitForOutput(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        ItemStack input1 =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack();

        ItemStack input2 =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_2_ID
                        )
                        .getStack();

        if (input1.isEmpty()
                || input2.isEmpty()) {

            delay = 1;

            return;
        }

        stage =
                Stage.WAITING_FOR_OUTPUT;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * Read the actual Anvil output.
     */
    private static void takeOutput(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        ItemStack output =
                handler
                        .getSlot(
                                AnvilScreenHandler.OUTPUT_ID
                        )
                        .getStack();

        /*
         * No output means the Anvil rejected the operation.
         */
        if (output.isEmpty()) {

            int cost =
                    handler.getLevelCost();

            if (cost > 39) {

                stopWithError(
                        client,
                        "Anvil Plus: Too Expensive!",
                        0xFF5555
                );

            } else if (cost > client.player.experienceLevel) {

                stopWithError(
                        client,
                        "Anvil Plus: Not Enough XP",
                        0xFF5555
                );

            } else {

                stopWithError(
                        client,
                        "Anvil Plus: Incompatible or invalid book",
                        0xFFAA00
                );
            }

            return;
        }

        int cost =
                handler.getLevelCost();

        /*
         * Vanilla Anvil maximum.
         */
        if (cost > 39) {

            stopWithError(
                    client,
                    "Anvil Plus: Too Expensive!",
                    0xFF5555
            );

            return;
        }

        /*
         * Not enough XP.
         */
        if (cost > client.player.experienceLevel) {

            stopWithError(
                    client,
                    "Anvil Plus: Not Enough XP",
                    0xFF5555
            );

            return;
        }

        /*
         * Take the actual result from the Anvil.
         */
        client.interactionManager.clickSlot(
                handler.syncId,
                AnvilScreenHandler.OUTPUT_ID,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        stage =
                Stage.WAITING_FOR_CLEAR;

        delay =
                ACTION_DELAY_TICKS;

        actionBar(
                client,
                "Anvil Plus: Enchanted",
                0x55FF55
        );
    }

    /*
     * Wait until the Anvil has completely cleared.
     */
    private static void waitForClear(
            AnvilScreenHandler handler
    ) {

        boolean input1Empty =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack()
                        .isEmpty();

        boolean input2Empty =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_2_ID
                        )
                        .getStack()
                        .isEmpty();

        boolean outputEmpty =
                handler
                        .getSlot(
                                AnvilScreenHandler.OUTPUT_ID
                        )
                        .getStack()
                        .isEmpty();

        if (!input1Empty
                || !input2Empty
                || !outputEmpty) {

            delay = 1;

            return;
        }

        selectedBoot =
                ItemStack.EMPTY;

        selectedBook =
                ItemStack.EMPTY;

        stage =
                Stage.IDLE;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * Find Netherite Boots anywhere in the inventory.
     *
     * Player inventory:
     * 0-8  = Hotbar
     * 9-35 = Main inventory
     */
    private static int findNextBoot(
            MinecraftClient client
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack stack =
                    client.player.getInventory().getStack(i);

            if (!stack.isEmpty()
                    && stack.isOf(Items.NETHERITE_BOOTS)) {

                return i;
            }
        }

        return -1;
    }

    /*
     * Find the exact selected book again.
     */
    private static int findExactBook(
            MinecraftClient client,
            ItemStack wanted
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack stack =
                    client.player.getInventory().getStack(i);

            if (!stack.isEmpty()
                    && stack.isOf(Items.ENCHANTED_BOOK)
                    && ItemStack.areItemsAndComponentsEqual(
                            stack,
                            wanted
                    )) {

                return i;
            }
        }

        return -1;
    }

    /*
     * Find a book that adds at least one new useful enchantment.
     *
     * Books containing multiple enchantments are allowed.
     */
    private static int findUsefulBook(
            MinecraftClient client,
            ItemStack boot
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack book =
                    client.player.getInventory().getStack(i);

            if (book.isEmpty()
                    || !book.isOf(Items.ENCHANTED_BOOK)) {

                continue;
            }

            if (hasUsefulAllowedEnchantment(
                    boot,
                    book
            )) {

                return i;
            }
        }

        return -1;
    }

    /*
     * Check whether a book contains at least one useful
     * enchantment that the boots do not already have at
     * the same or higher level.
     *
     * This is intentionally based on RegistryEntry rather
     * than Registries.ENCHANTMENT, which is not available
     * in this API version in the way the old code expected.
     */
    private static boolean hasUsefulAllowedEnchantment(
            ItemStack boot,
            ItemStack book
    ) {

        var bootEnchants =
                EnchantmentHelper.getEnchantments(boot);

        var bookEnchants =
                EnchantmentHelper.getEnchantments(book);

        for (RegistryEntry<Enchantment> entry :
                bookEnchants.getEnchantments()) {

            /*
             * Only the seven requested enchantments.
             */
            if (!isAllowed(entry)) {
                continue;
            }

            int bookLevel =
                    bookEnchants.getLevel(entry);

            int bootLevel =
                    bootEnchants.getLevel(entry);

            /*
             * If the boot already has the same or
             * higher level, this enchantment gives
             * us nothing new.
             */
            if (bootLevel >= bookLevel) {
                continue;
            }

            /*
             * Netherite Boots must support the enchantment.
             */
            if (!entry.value().isSupportedItem(boot)) {
                continue;
            }

            /*
             * At least one useful enchantment exists.
             *
             * This is why multi-enchantment books work.
             */
            return true;
        }

        return false;
    }

    /*
     * Allowed enchantments.
     */
    private static boolean isAllowed(
            RegistryEntry<Enchantment> entry
    ) {

        return entry.matchesKey(Enchantments.UNBREAKING)
                || entry.matchesKey(Enchantments.FEATHER_FALLING)
                || entry.matchesKey(Enchantments.BLAST_PROTECTION)
                || entry.matchesKey(Enchantments.THORNS)
                || entry.matchesKey(Enchantments.DEPTH_STRIDER)
                || entry.matchesKey(Enchantments.MENDING)
                || entry.matchesKey(Enchantments.SOUL_SPEED);
    }

    /*
     * Convert PlayerInventory slot numbers to
     * AnvilScreenHandler slot numbers.
     *
     * Player inventory:
     *
     * 0-8   = Hotbar
     * 9-35  = Main inventory
     *
     * Anvil:
     *
     * 3-29  = Main inventory
     * 30-38 = Hotbar
     */
    private static int inventoryToHandlerSlot(
            int inventorySlot
    ) {

        if (inventorySlot >= 0
                && inventorySlot < 9) {

            return 30 + inventorySlot;
        }

        return 3 + (inventorySlot - 9);
    }

    /*
     * Move an inventory item into the Anvil
     * using normal QUICK_MOVE behavior.
     */
    private static void clickInventorySlot(
            MinecraftClient client,
            AnvilScreenHandler handler,
            int inventorySlot
    ) {

        client.interactionManager.clickSlot(
                handler.syncId,
                inventoryToHandlerSlot(inventorySlot),
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
    }

    /*
     * Make sure both Anvil input slots are empty.
     */
    private static boolean isInputEmpty(
            AnvilScreenHandler handler
    ) {

        return handler
                .getSlot(
                        AnvilScreenHandler.INPUT_1_ID
                )
                .getStack()
                .isEmpty()

                && handler
                .getSlot(
                        AnvilScreenHandler.INPUT_2_ID
                )
                .getStack()
                .isEmpty();
    }

    /*
     * Stop the automation and permanently turn it OFF
     * until the user toggles it back ON.
     */
    private static void stopWithError(
            MinecraftClient client,
            String text,
            int color
    ) {

        enabled = false;

        resetState();

        saveConfig();

        actionBar(
                client,
                text,
                color
        );
    }

    /*
     * Reset current Anvil operation.
     */
    private static void resetState() {

        lastHandler = null;

        delay = 0;

        stage = Stage.IDLE;

        selectedBoot =
                ItemStack.EMPTY;

        selectedBook =
                ItemStack.EMPTY;
    }

    /*
     * Display a colored message above the hotbar.
     */
    private static void actionBar(
            MinecraftClient client,
            String text,
            int color
    ) {

        if (client.player != null) {

            client.player.sendMessage(
                    Text.literal(text)
                            .styled(
                                    style ->
                                            style.withColor(color)
                            ),
                    true
            );
        }
    }

    /*
     * Config file location.
     */
    private static java.nio.file.Path configPath() {

        return java.nio.file.Path.of(
                "config",
                "anvil_plus.properties"
        );
    }

    /*
     * Load ON/OFF state from disk.
     */
    private static void loadConfig() {

        java.nio.file.Path path =
                configPath();

        if (!java.nio.file.Files.exists(path)) {
            return;
        }

        try {

            for (String line :
                    java.nio.file.Files.readAllLines(path)) {

                if (line.startsWith("enabled=")) {

                    enabled =
                            Boolean.parseBoolean(
                                    line.substring(
                                            "enabled=".length()
                                    )
                            );
                }
            }

        } catch (java.io.IOException ignored) {
        }
    }

    /*
     * Save ON/OFF state to disk.
     */
    private static void saveConfig() {

        java.nio.file.Path path =
                configPath();

        try {

            java.nio.file.Files.createDirectories(
                    path.getParent()
            );

            java.nio.file.Files.writeString(
                    path,
                    "enabled="
                            + enabled
                            + System.lineSeparator()
            );

        } catch (java.io.IOException ignored) {
        }
    }
}
