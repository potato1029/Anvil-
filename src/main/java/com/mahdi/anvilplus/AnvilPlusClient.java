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
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AnvilPlusClient implements ClientModInitializer {

    private static final String MOD_ID = "anvil_plus";

    /*
     * Minecraft runs at 20 ticks per second.
     *
     * 5 ticks = 250 milliseconds.
     */
    private static final int ACTION_DELAY_TICKS = 5;

    /*
     * Small delay after opening a new Anvil.
     */
    private static final int OPEN_DELAY_TICKS = 5;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(
                    Identifier.of(MOD_ID, "controls")
            );

    private static KeyBinding toggleKey;

    /*
     * IMPORTANT:
     * The mod does NOT automatically turn OFF because of XP.
     *
     * Only the player can turn it OFF using the keybind.
     */
    private static boolean enabled = true;

    private static AnvilScreenHandler lastHandler;

    private static int delay = 0;

    private static Stage stage = Stage.IDLE;

    private static ItemStack selectedBoot = ItemStack.EMPTY;
    private static ItemStack selectedBook = ItemStack.EMPTY;

    private enum Stage {
        IDLE,

        /*
         * Boot has been moved into Anvil slot 1.
         */
        WAITING_FOR_BOOT,

        /*
         * Book has been moved into Anvil slot 2.
         */
        WAITING_FOR_BOOK,

        /*
         * Waiting for the Anvil result.
         */
        WAITING_FOR_OUTPUT,

        /*
         * Output was taken.
         */
        WAITING_FOR_CLEAR
    }

    @Override
    public void onInitializeClient() {

        /*
         * Default key = P
         *
         * Player can change this from:
         *
         * Options -> Controls -> Key Binds
         */
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

        /*
         * If manually OFF, do absolutely nothing.
         */
        if (!enabled) {
            return;
        }

        /*
         * Player / interaction manager must exist.
         */
        if (client.player == null ||
                client.interactionManager == null) {
            return;
        }

        /*
         * The mod works ONLY while an Anvil is open.
         *
         * Closing the Anvil stops the automation,
         * but does NOT turn the mod OFF.
         */
        if (!(client.currentScreen instanceof AnvilScreen screen)) {

            resetState();

            return;
        }

        AnvilScreenHandler handler =
                screen.getScreenHandler();

        /*
         * New Anvil GUI detected.
         */
        if (handler != lastHandler) {

            lastHandler = handler;

            delay = OPEN_DELAY_TICKS;

            stage = Stage.IDLE;

            selectedBoot = ItemStack.EMPTY;

            selectedBook = ItemStack.EMPTY;

            return;
        }

        /*
         * Wait between operations.
         */
        if (delay > 0) {

            delay--;

            return;
        }

        switch (stage) {

            case IDLE ->
                    startNext(
                            client,
                            handler
                    );

            case WAITING_FOR_BOOT ->
                    putBook(
                            client,
                            handler
                    );

            case WAITING_FOR_BOOK ->
                    waitForOutput(
                            client,
                            handler
                    );

            case WAITING_FOR_OUTPUT ->
                    takeOutput(
                            client,
                            handler
                    );

            case WAITING_FOR_CLEAR ->
                    waitForClear(
                            client,
                            handler
                    );
        }
    }

    /*
     * Finds the next Netherite Boot and a useful enchanted book.
     */
    private static void startNext(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        /*
         * Do not interfere with manually placed items.
         */
        if (!isInputEmpty(handler)) {

            /*
             * Instead of turning OFF permanently,
             * simply wait.
             */
            delay = 5;

            return;
        }

        /*
         * Find a Netherite Boot anywhere in inventory.
         *
         * Hotbar and normal inventory are both included.
         */
        int bootSlot = findNextBoot(client);

        if (bootSlot < 0) {

            /*
             * Nothing left to do.
             */
            delay = 20;

            return;
        }

        ItemStack boot =
                client.player
                        .getInventory()
                        .getStack(bootSlot);

        /*
         * Find a book that actually adds something
         * useful to this particular boot.
         */
        int bookSlot =
                findUsefulBook(
                        client,
                        boot
                );

        /*
         * IMPORTANT:
         *
         * If no book is currently useful,
         * DO NOT turn OFF.
         *
         * Wait and check again later.
         */
        if (bookSlot < 0) {

            delay = 10;

            return;
        }

        selectedBoot =
                boot.copy();

        selectedBook =
                client.player
                        .getInventory()
                        .getStack(bookSlot)
                        .copy();

        /*
         * Move the selected Netherite Boot
         * into Anvil slot 1.
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
     * Wait until the boot actually arrives
     * in Anvil slot 1, then put the book in.
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

        /*
         * Server hasn't synchronized yet.
         */
        if (input.isEmpty()) {

            delay = 1;

            return;
        }

        /*
         * Find the exact selected book again.
         */
        int bookSlot =
                findExactBook(
                        client,
                        selectedBook
                );

        /*
         * Book may have moved.
         *
         * Try again instead of stopping.
         */
        if (bookSlot < 0) {

            /*
             * Clear the selected data and restart scanning.
             */
            selectedBook =
                    ItemStack.EMPTY;

            stage =
                    Stage.IDLE;

            delay =
                    ACTION_DELAY_TICKS;

            return;
        }

        /*
         * Put the book into Anvil slot 2.
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
     * Wait until both Anvil input slots contain items.
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

        if (input1.isEmpty() ||
                input2.isEmpty()) {

            delay = 1;

            return;
        }

        /*
         * Both inputs exist.
         *
         * Now wait for Anvil result.
         */
        stage =
                Stage.WAITING_FOR_OUTPUT;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * Handles the result slot.
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

        int cost =
                handler.getLevelCost();

        /*
         * =====================================================
         * NO OUTPUT
         * =====================================================
         */
        if (output.isEmpty()) {

            /*
             * Too Expensive.
             *
             * DO NOT turn the mod OFF.
             *
             * Since the user wants the mod to wait for XP,
             * we keep the Anvil inputs there and check again.
             */
            if (cost > 39) {

                /*
                 * Too Expensive is NOT caused by XP.
                 *
                 * This will not become cheaper just by gaining XP.
                 *
                 * Stop this operation and clear the inputs safely.
                 */
                stopCurrentOperation(
                        client,
                        "Anvil Plus: Too Expensive!",
                        0xFF5555
                );

                return;
            }

            /*
             * Not enough XP.
             *
             * IMPORTANT:
             * Keep the boot + book in the Anvil.
             *
             * Wait until the player gains enough XP.
             */
            if (cost > client.player.experienceLevel) {

                delay = 10;

                return;
            }

            /*
             * XP appears sufficient but no output.
             *
             * This normally means the combination is invalid
             * or incompatible.
             */
            stopCurrentOperation(
                    client,
                    "Anvil Plus: Incompatible or invalid book",
                    0xFFAA00
            );

            return;
        }

        /*
         * =====================================================
         * OUTPUT EXISTS
         * =====================================================
         */

        /*
         * If the result costs more than 39 levels,
         * vanilla Anvil normally displays Too Expensive.
         */
        if (cost > 39) {

            stopCurrentOperation(
                    client,
                    "Anvil Plus: Too Expensive!",
                    0xFF5555
            );

            return;
        }

        /*
         * XP is not enough yet.
         *
         * KEEP EVERYTHING IN THE ANVIL.
         */
        if (cost > client.player.experienceLevel) {

            delay = 10;

            return;
        }

        /*
         * We have enough XP.
         *
         * Take the actual server-authoritative Anvil result.
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
     * Wait until Anvil slots become empty.
     *
     * Then immediately search for another boot.
     */
    private static void waitForClear(
            MinecraftClient client,
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

        /*
         * Server hasn't finished synchronizing.
         */
        if (!input1Empty ||
                !input2Empty ||
                !outputEmpty) {

            delay = 1;

            return;
        }

        /*
         * VERY IMPORTANT:
         *
         * We do NOT care where the enchanted boot went.
         *
         * It may be in:
         * - Hotbar
         * - Main inventory
         * - Another inventory position
         *
         * We simply clear our old references and search again.
         */
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
     * Finds any Netherite Boots in the player's inventory.
     */
    private static int findNextBoot(
            MinecraftClient client
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack stack =
                    client.player
                            .getInventory()
                            .getStack(i);

            if (!stack.isEmpty()
                    && stack.isOf(Items.NETHERITE_BOOTS)) {

                /*
                 * Only select boots that still have
                 * at least one useful allowed enchantment missing.
                 *
                 * This prevents already-completed boots
                 * from blocking the process.
                 */
                if (hasAnyUsefulBook(
                        client,
                        stack
                )) {

                    return i;
                }
            }
        }

        return -1;
    }

    /*
     * Checks whether ANY book in inventory can add
     * a new allowed enchantment to the boot.
     */
    private static boolean hasAnyUsefulBook(
            MinecraftClient client,
            ItemStack boot
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack book =
                    client.player
                            .getInventory()
                            .getStack(i);

            if (book.isEmpty()
                    || !book.isOf(Items.ENCHANTED_BOOK)) {

                continue;
            }

            if (hasUsefulAllowedEnchantment(
                    boot,
                    book
            )) {

                return true;
            }
        }

        return false;
    }

    /*
     * Finds the exact same enchanted book.
     */
    private static int findExactBook(
            MinecraftClient client,
            ItemStack wanted
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack stack =
                    client.player
                            .getInventory()
                            .getStack(i);

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
     * Finds a book containing at least one
     * allowed enchantment that is higher/newer
     * than the enchantment currently on the boots.
     */
    private static int findUsefulBook(
            MinecraftClient client,
            ItemStack boot
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack book =
                    client.player
                            .getInventory()
                            .getStack(i);

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
     * Checks all enchantments on a book.
     *
     * IMPORTANT:
     *
     * A book may have MULTIPLE enchantments.
     *
     * Example:
     *
     * Unbreaking III
     * + Mending
     *
     * is valid if at least one of those
     * enchantments can improve the boot.
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
             * Get the enchantment ID safely.
             *
             * We DON'T use Registries.ENCHANTMENT,
             * because that API is not available in this
             * Minecraft 1.21.11 environment.
             */
            Identifier id =
                    entry.getKey()
                            .map(key -> key.getValue())
                            .orElse(null);

            if (!isAllowed(id)) {
                continue;
            }

            int bookLevel =
                    bookEnchants.getLevel(entry);

            int bootLevel =
                    bootEnchants.getLevel(entry);

            /*
             * If the boot already has the same
             * or a higher level, this enchantment
             * is NOT useful.
             */
            if (bootLevel >= bookLevel) {
                continue;
            }

            /*
             * Check whether this enchantment supports
             * Netherite Boots.
             */
            if (!entry.value().isSupportedItem(boot)) {
                continue;
            }

            /*
             * At least one useful enchantment exists.
             */
            return true;
        }

        return false;
    }

    /*
     * The ONLY enchantments Anvil Plus is allowed
     * to work with.
     */
    private static boolean isAllowed(
            Identifier id
    ) {

        if (id == null) {
            return false;
        }

        return id.equals(
                Identifier.of(
                        "minecraft",
                        "unbreaking"
                )
        )
                || id.equals(
                Identifier.of(
                        "minecraft",
                        "feather_falling"
                )
        )
                || id.equals(
                Identifier.of(
                        "minecraft",
                        "blast_protection"
                )
        )
                || id.equals(
                Identifier.of(
                        "minecraft",
                        "thorns"
                )
        )
                || id.equals(
                Identifier.of(
                        "minecraft",
                        "depth_strider"
                )
        )
                || id.equals(
                Identifier.of(
                        "minecraft",
                        "mending"
                )
        )
                || id.equals(
                Identifier.of(
                        "minecraft",
                        "soul_speed"
                )
        );
    }

    /*
     * Converts player's inventory slot
     * into the correct Anvil handler slot.
     *
     * Player inventory:
     *
     * 0-8   = Hotbar
     * 9-35  = Main inventory
     *
     * Anvil handler:
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

        return 3 +
                (inventorySlot - 9);
    }

    /*
     * Moves an inventory item into the Anvil
     * using the normal QUICK_MOVE action.
     */
    private static void clickInventorySlot(
            MinecraftClient client,
            AnvilScreenHandler handler,
            int inventorySlot
    ) {

        client.interactionManager.clickSlot(
                handler.syncId,
                inventoryToHandlerSlot(
                        inventorySlot
                ),
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
    }

    /*
     * Checks whether both Anvil input slots are empty.
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

                &&

                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_2_ID
                        )
                        .getStack()
                        .isEmpty();
    }

    /*
     * Stops ONLY the current operation.
     *
     * IMPORTANT:
     *
     * This does NOT turn the entire mod OFF.
     *
     * The player can continue using Anvil Plus
     * after the problematic situation is gone.
     */
    private static void stopCurrentOperation(
            MinecraftClient client,
            String text,
            int color
    ) {

        actionBar(
                client,
                text,
                color
        );

        /*
         * We don't set:
         *
         * enabled = false;
         *
         * because the user explicitly wants
         * manual ON/OFF only.
         */

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
     * Resets temporary automation state.
     *
     * Does NOT change enabled.
     */
    private static void resetState() {

        lastHandler = null;

        delay = 0;

        stage =
                Stage.IDLE;

        selectedBoot =
                ItemStack.EMPTY;

        selectedBook =
                ItemStack.EMPTY;
    }

    /*
     * Sends colored text to the Action Bar.
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
     * Config file:
     *
     * config/anvil_plus.properties
     *
     * Stores ON/OFF state between launches.
     */
    private static Path configPath() {

        return Path.of(
                "config",
                "anvil_plus.properties"
        );
    }

    /*
     * Loads saved ON/OFF state.
     */
    private static void loadConfig() {

        Path path =
                configPath();

        if (!Files.exists(path)) {
            return;
        }

        try {

            for (String line :
                    Files.readAllLines(path)) {

                if (line.startsWith(
                        "enabled="
                )) {

                    enabled =
                            Boolean.parseBoolean(
                                    line.substring(
                                            "enabled=".length()
                                    )
                            );
                }
            }

        } catch (IOException ignored) {
        }
    }

    /*
     * Saves ON/OFF state.
     */
    private static void saveConfig() {

        Path path =
                configPath();

        try {

            Files.createDirectories(
                    path.getParent()
            );

            Files.writeString(
                    path,
                    "enabled="
                            + enabled
                            + System.lineSeparator()
            );

        } catch (IOException ignored) {
        }
    }
}
