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
import net.minecraft.registry.RegistryKey;
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
     * =========================================================
     * SPEED
     * =========================================================
     *
     * 3 ticks = approximately 150ms.
     */
    private static final int ACTION_DELAY_TICKS = 3;
    private static final int OPEN_DELAY_TICKS = 3;
    private static final int WAIT_TICKS = 5;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(
                    Identifier.of(MOD_ID, "controls")
            );

    private static KeyBinding anvilPlusKey;
    private static KeyBinding enchantPlusKey;

    /*
     * =========================================================
     * ENABLE STATES
     * =========================================================
     *
     * These are completely independent.
     *
     * Neither feature is automatically disabled because of:
     *
     * - insufficient XP
     * - missing book
     * - incompatible enchantment
     * - closing the Anvil
     * - no suitable item
     * - Too Expensive
     *
     * They can ONLY be changed by their own keybind.
     */
    private static boolean anvilPlusEnabled = true;
    private static boolean enchantPlusEnabled = false;

    /*
     * =========================================================
     * COMMON STATE
     * =========================================================
     */

    private static AnvilScreenHandler lastHandler;
    private static int delay = 0;

    private static Stage stage = Stage.IDLE;

    /*
     * =========================================================
     * ANVIL PLUS - BOOT DATA
     * =========================================================
     */

    private static ItemStack selectedBoot = ItemStack.EMPTY;
    private static ItemStack selectedBootBook = ItemStack.EMPTY;

    /*
     * =========================================================
     * ENCHANT PLUS - BOOK DATA
     * =========================================================
     */

    private static ItemStack selectedFirstBook = ItemStack.EMPTY;
    private static ItemStack selectedSecondBook = ItemStack.EMPTY;

    /*
     * =========================================================
     * STAGES
     * =========================================================
     */

    private enum Stage {

        IDLE,

        /*
         * Anvil Plus
         */
        BOOT_WAITING_FOR_BOOT,
        BOOT_WAITING_FOR_BOOK,
        BOOT_WAITING_FOR_OUTPUT,
        BOOT_WAITING_FOR_CLEAR,

        /*
         * Enchant Plus
         */
        ENCHANT_WAITING_FOR_FIRST,
        ENCHANT_WAITING_FOR_SECOND,
        ENCHANT_WAITING_FOR_OUTPUT,
        ENCHANT_WAITING_FOR_CLEAR
    }

    /*
     * =========================================================
     * ENCHANT PLUS PAIRS
     * =========================================================
     *
     * Slot 1 -> Slot 2
     *
     * ONLY these combinations are allowed.
     */

    private static final BookPair[] ENCHANT_PLUS_PAIRS = {

            /*
             * Thorns III + Mending
             */
            new BookPair(
                    Enchantments.THORNS,
                    3,
                    Enchantments.MENDING,
                    1
            ),

            /*
             * Blast Protection IV + Feather Falling IV
             */
            new BookPair(
                    Enchantments.BLAST_PROTECTION,
                    4,
                    Enchantments.FEATHER_FALLING,
                    4
            ),

            /*
             * Depth Strider III + Unbreaking III
             */
            new BookPair(
                    Enchantments.DEPTH_STRIDER,
                    3,
                    Enchantments.UNBREAKING,
                    3
            )
    };

    private static class BookPair {

        final RegistryKey<Enchantment> first;
        final int firstLevel;

        final RegistryKey<Enchantment> second;
        final int secondLevel;

        BookPair(
                RegistryKey<Enchantment> first,
                int firstLevel,
                RegistryKey<Enchantment> second,
                int secondLevel
        ) {
            this.first = first;
            this.firstLevel = firstLevel;

            this.second = second;
            this.secondLevel = secondLevel;
        }
    }

    /*
     * =========================================================
     * INITIALIZATION
     * =========================================================
     */

    @Override
    public void onInitializeClient() {

        /*
         * ANVIL PLUS
         *
         * Default key = P
         */
        anvilPlusKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.anvil_plus.toggle",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_P,
                        CATEGORY
                )
        );

        /*
         * ENCHANT PLUS
         *
         * Default key = O
         */
        enchantPlusKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.anvil_plus.enchant_plus",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_O,
                        CATEGORY
                )
        );

        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(
                AnvilPlusClient::tick
        );
    }

    /*
     * =========================================================
     * MAIN TICK
     * =========================================================
     */

    private static void tick(MinecraftClient client) {

        /*
         * =====================================================
         * ANVIL PLUS TOGGLE
         * =====================================================
         */

        while (anvilPlusKey.wasPressed()) {

            anvilPlusEnabled = !anvilPlusEnabled;

            /*
             * Only stop the current Anvil Plus operation.
             *
             * Do NOT modify Enchant Plus.
             */
            resetAnvilPlusState();

            saveConfig();

            if (anvilPlusEnabled) {

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
         * =====================================================
         * ENCHANT PLUS TOGGLE
         * =====================================================
         */

        while (enchantPlusKey.wasPressed()) {

            enchantPlusEnabled = !enchantPlusEnabled;

            /*
             * Only stop the current Enchant Plus operation.
             *
             * Do NOT modify Anvil Plus.
             */
            resetEnchantPlusState();

            saveConfig();

            if (enchantPlusEnabled) {

                actionBar(
                        client,
                        "Enchant Plus: ON",
                        0xAA55FF
                );

            } else {

                actionBar(
                        client,
                        "Enchant Plus: OFF",
                        0xFF5555
                );
            }
        }

        /*
         * Nothing enabled.
         */
        if (!anvilPlusEnabled && !enchantPlusEnabled) {
            return;
        }

        if (client.player == null
                || client.interactionManager == null) {
            return;
        }

        /*
         * Both systems work only while Anvil is open.
         *
         * Closing Anvil NEVER turns either feature OFF.
         */
        if (!(client.currentScreen instanceof AnvilScreen screen)) {

            resetOperationState();

            return;
        }

        AnvilScreenHandler handler =
                screen.getScreenHandler();

        /*
         * New Anvil screen.
         */
        if (handler != lastHandler) {

            lastHandler = handler;

            stage = Stage.IDLE;

            delay = OPEN_DELAY_TICKS;

            clearSelections();

            return;
        }

        if (delay > 0) {

            delay--;

            return;
        }

        switch (stage) {

            case IDLE ->
                    chooseNextTask(
                            client,
                            handler
                    );

            /*
             * ==================================================
             * ANVIL PLUS
             * ==================================================
             */

            case BOOT_WAITING_FOR_BOOT ->
                    putBootBook(
                            client,
                            handler
                    );

            case BOOT_WAITING_FOR_BOOK ->
                    waitForBootInputs(
                            handler
                    );

            case BOOT_WAITING_FOR_OUTPUT ->
                    takeBootOutput(
                            client,
                            handler
                    );

            case BOOT_WAITING_FOR_CLEAR ->
                    waitForBootClear(
                            handler
                    );

            /*
             * ==================================================
             * ENCHANT PLUS
             * ==================================================
             */

            case ENCHANT_WAITING_FOR_FIRST ->
                    putSecondEnchantBook(
                            client,
                            handler
                    );

            case ENCHANT_WAITING_FOR_SECOND ->
                    waitForEnchantInputs(
                            handler
                    );

            case ENCHANT_WAITING_FOR_OUTPUT ->
                    takeEnchantOutput(
                            client,
                            handler
                    );

            case ENCHANT_WAITING_FOR_CLEAR ->
                    waitForEnchantClear(
                            handler
                    );
        }
    }

    /*
     * =========================================================
     * TASK SELECTION
     * =========================================================
     *
     * Enchant Plus has priority.
     *
     * If there is no valid Enchant Plus pair,
     * Anvil Plus gets a chance.
     */

    private static void chooseNextTask(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        if (!isInputEmpty(handler)) {

            delay = WAIT_TICKS;

            return;
        }

        /*
         * ENCHANT PLUS FIRST
         */
        if (enchantPlusEnabled) {

            BookPairResult pair =
                    findEnchantPlusPair(client);

            if (pair != null) {

                startEnchantPair(
                        client,
                        handler,
                        pair
                );

                return;
            }
        }

        /*
         * NORMAL ANVIL PLUS
         */
        if (anvilPlusEnabled) {

            startNextBoot(
                    client,
                    handler
            );

            return;
        }

        delay = WAIT_TICKS;
    }

    /*
     * =========================================================
     * ENCHANT PLUS
     * =========================================================
     */

    private static class BookPairResult {

        final int firstSlot;
        final int secondSlot;

        BookPairResult(
                int firstSlot,
                int secondSlot
        ) {
            this.firstSlot = firstSlot;
            this.secondSlot = secondSlot;
        }
    }

    /*
     * Search ONLY for the three requested combinations.
     */

    private static BookPairResult findEnchantPlusPair(
            MinecraftClient client
    ) {

        for (BookPair pair : ENCHANT_PLUS_PAIRS) {

            int firstSlot =
                    findExactSingleEnchantBook(
                            client,
                            pair.first,
                            pair.firstLevel,
                            -1
                    );

            if (firstSlot < 0) {
                continue;
            }

            int secondSlot =
                    findExactSingleEnchantBook(
                            client,
                            pair.second,
                            pair.secondLevel,
                            firstSlot
                    );

            if (secondSlot < 0) {
                continue;
            }

            return new BookPairResult(
                    firstSlot,
                    secondSlot
            );
        }

        return null;
    }

    /*
     * Enchant Plus deliberately requires EXACTLY ONE
     * enchantment on each source book.
     *
     * This prevents accidental combinations.
     */

    private static int findExactSingleEnchantBook(
            MinecraftClient client,
            RegistryKey<Enchantment> wanted,
            int wantedLevel,
            int ignoredSlot
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            if (i == ignoredSlot) {
                continue;
            }

            ItemStack stack =
                    client.player
                            .getInventory()
                            .getStack(i);

            if (stack.isEmpty()
                    || !stack.isOf(Items.ENCHANTED_BOOK)) {
                continue;
            }

            if (isExactSingleEnchantBook(
                    stack,
                    wanted,
                    wantedLevel
            )) {

                return i;
            }
        }

        return -1;
    }

    private static boolean isExactSingleEnchantBook(
            ItemStack book,
            RegistryKey<Enchantment> wanted,
            int wantedLevel
    ) {

        var enchantments =
                EnchantmentHelper.getEnchantments(book);

        if (enchantments.getEnchantments().size() != 1) {
            return false;
        }

        for (RegistryEntry<Enchantment> entry :
                enchantments.getEnchantments()) {

            if (!entry.matchesKey(wanted)) {
                return false;
            }

            return enchantments.getLevel(entry)
                    == wantedLevel;
        }

        return false;
    }

    /*
     * Put first book into Anvil Slot 1.
     */

    private static void startEnchantPair(
            MinecraftClient client,
            AnvilScreenHandler handler,
            BookPairResult pair
    ) {

        selectedFirstBook =
                client.player
                        .getInventory()
                        .getStack(pair.firstSlot)
                        .copy();

        selectedSecondBook =
                client.player
                        .getInventory()
                        .getStack(pair.secondSlot)
                        .copy();

        clickInventorySlot(
                client,
                handler,
                pair.firstSlot
        );

        stage =
                Stage.ENCHANT_WAITING_FOR_FIRST;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * Put second book into Anvil Slot 2.
     */

    private static void putSecondEnchantBook(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        ItemStack firstInput =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack();

        if (firstInput.isEmpty()) {

            delay = 1;

            return;
        }

        int secondSlot =
                findExactBook(
                        client,
                        selectedSecondBook
                );

        if (secondSlot < 0) {

            /*
             * NEVER disable Enchant Plus.
             */
            delay = WAIT_TICKS;

            return;
        }

        clickInventorySlot(
                client,
                handler,
                secondSlot
        );

        stage =
                Stage.ENCHANT_WAITING_FOR_SECOND;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void waitForEnchantInputs(
            AnvilScreenHandler handler
    ) {

        ItemStack first =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack();

        ItemStack second =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_2_ID
                        )
                        .getStack();

        if (first.isEmpty()
                || second.isEmpty()) {

            delay = 1;

            return;
        }

        stage =
                Stage.ENCHANT_WAITING_FOR_OUTPUT;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * Wait for valid result and sufficient XP.
     *
     * IMPORTANT:
     *
     * If XP is insufficient, we WAIT.
     * We do NOT turn OFF.
     */

    private static void takeEnchantOutput(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        int cost =
                handler.getLevelCost();

        ItemStack output =
                handler
                        .getSlot(
                                AnvilScreenHandler.OUTPUT_ID
                        )
                        .getStack();

        /*
         * Waiting for XP.
         */
        if (cost > client.player.experienceLevel
                && cost <= 39) {

            delay = WAIT_TICKS;

            return;
        }

        /*
         * Too expensive.
         *
         * Keep Enchant Plus ON.
         *
         * We simply wait instead of disabling the feature.
         */
        if (cost > 39) {

            delay = 20;

            return;
        }

        /*
         * Server has not calculated output yet.
         */
        if (output.isEmpty()) {

            delay = WAIT_TICKS;

            return;
        }

        /*
         * Take actual server-authoritative result.
         */
        client.interactionManager.clickSlot(
                handler.syncId,
                AnvilScreenHandler.OUTPUT_ID,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        actionBar(
                client,
                "Enchant Plus: Combined!",
                0x55FF55
        );

        stage =
                Stage.ENCHANT_WAITING_FOR_CLEAR;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void waitForEnchantClear(
            AnvilScreenHandler handler
    ) {

        if (!allAnvilSlotsEmpty(handler)) {

            delay = 1;

            return;
        }

        selectedFirstBook =
                ItemStack.EMPTY;

        selectedSecondBook =
                ItemStack.EMPTY;

        stage =
                Stage.IDLE;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * =========================================================
     * ANVIL PLUS - NETHERITE BOOTS
     * =========================================================
     */

    private static void startNextBoot(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        if (!isInputEmpty(handler)) {

            delay = WAIT_TICKS;

            return;
        }

        /*
         * IMPORTANT:
         *
         * findNextBoot() already skips boots that cannot
         * receive another useful enchantment.
         *
         * This means one bad/already-completed boot does NOT
         * stop the entire automation.
         */

        int bootSlot =
                findNextBoot(client);

        if (bootSlot < 0) {

            delay = 10;

            return;
        }

        ItemStack boot =
                client.player
                        .getInventory()
                        .getStack(bootSlot);

        int bookSlot =
                findUsefulBootBook(
                        client,
                        boot
                );

        if (bookSlot < 0) {

            delay = 10;

            return;
        }

        selectedBoot =
                boot.copy();

        selectedBootBook =
                client.player
                        .getInventory()
                        .getStack(bookSlot)
                        .copy();

        /*
         * Boot -> Anvil Slot 1.
         */
        clickInventorySlot(
                client,
                handler,
                bootSlot
        );

        stage =
                Stage.BOOT_WAITING_FOR_BOOT;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void putBootBook(
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

        int bookSlot =
                findExactBook(
                        client,
                        selectedBootBook
                );

        if (bookSlot < 0) {

            /*
             * Do NOT turn Anvil Plus OFF.
             */
            delay = WAIT_TICKS;

            return;
        }

        /*
         * Book -> Anvil Slot 2.
         */
        clickInventorySlot(
                client,
                handler,
                bookSlot
        );

        stage =
                Stage.BOOT_WAITING_FOR_BOOK;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void waitForBootInputs(
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
                Stage.BOOT_WAITING_FOR_OUTPUT;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * Wait for XP if necessary.
     *
     * NEVER disable Anvil Plus.
     */

    private static void takeBootOutput(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        int cost =
                handler.getLevelCost();

        ItemStack output =
                handler
                        .getSlot(
                                AnvilScreenHandler.OUTPUT_ID
                        )
                        .getStack();

        /*
         * Not enough XP.
         *
         * Stay here until XP is sufficient.
         */
        if (cost > client.player.experienceLevel
                && cost <= 39) {

            delay = WAIT_TICKS;

            return;
        }

        /*
         * Too Expensive.
         *
         * Keep Anvil Plus enabled.
         */
        if (cost > 39) {

            delay = 20;

            return;
        }

        /*
         * No result yet.
         */
        if (output.isEmpty()) {

            delay = WAIT_TICKS;

            return;
        }

        /*
         * Take actual Anvil result.
         */
        client.interactionManager.clickSlot(
                handler.syncId,
                AnvilScreenHandler.OUTPUT_ID,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        actionBar(
                client,
                "Anvil Plus: Enchanted!",
                0x55FF55
        );

        stage =
                Stage.BOOT_WAITING_FOR_CLEAR;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void waitForBootClear(
            AnvilScreenHandler handler
    ) {

        if (!allAnvilSlotsEmpty(handler)) {

            delay = 1;

            return;
        }

        selectedBoot =
                ItemStack.EMPTY;

        selectedBootBook =
                ItemStack.EMPTY;

        stage =
                Stage.IDLE;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * =========================================================
     * FIND NETHERITE BOOT
     * =========================================================
     *
     * Search entire player inventory.
     *
     * Hotbar and main inventory are both included.
     */

    private static int findNextBoot(
            MinecraftClient client
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack boot =
                    client.player
                            .getInventory()
                            .getStack(i);

            if (boot.isEmpty()
                    || !boot.isOf(Items.NETHERITE_BOOTS)) {
                continue;
            }

            /*
             * If this boot has no useful book,
             * SKIP IT and continue searching.
             */
            if (findUsefulBootBook(
                    client,
                    boot
            ) >= 0) {

                return i;
            }
        }

        return -1;
    }

    /*
     * =========================================================
     * FIND USEFUL BOOT BOOK
     * =========================================================
     */

    private static int findUsefulBootBook(
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

            if (hasUsefulBootEnchantment(
                    boot,
                    book
            )) {

                return i;
            }
        }

        return -1;
    }

    /*
     * =========================================================
     * CHECK BOOT ENCHANTMENT
     * =========================================================
     */

    private static boolean hasUsefulBootEnchantment(
            ItemStack boot,
            ItemStack book
    ) {

        var bootEnchantments =
                EnchantmentHelper.getEnchantments(boot);

        var bookEnchantments =
                EnchantmentHelper.getEnchantments(book);

        for (RegistryEntry<Enchantment> entry :
                bookEnchantments.getEnchantments()) {

            /*
             * Ignore enchantments outside the allowed list.
             */
            if (!isAllowedBootEnchantment(entry)) {
                continue;
            }

            int bookLevel =
                    bookEnchantments.getLevel(entry);

            int bootLevel =
                    bootEnchantments.getLevel(entry);

            /*
             * If boot already has the same or higher level,
             * this book is NOT useful for this boot.
             */
            if (bootLevel >= bookLevel) {
                continue;
            }

            /*
             * Netherite Boots support this enchantment.
             */
            if (!entry.value().isSupportedItem(boot)) {
                continue;
            }

            return true;
        }

        return false;
    }

    /*
     * =========================================================
     * ALLOWED BOOT ENCHANTMENTS
     * =========================================================
     *
     * ONLY:
     *
     * Unbreaking III
     * Feather Falling IV
     * Blast Protection IV
     * Thorns III
     * Depth Strider III
     * Mending
     * Soul Speed III
     */

    private static boolean isAllowedBootEnchantment(
            RegistryEntry<Enchantment> entry
    ) {

        return entry.matchesKey(
                Enchantments.UNBREAKING
        )
                || entry.matchesKey(
                Enchantments.FEATHER_FALLING
        )
                || entry.matchesKey(
                Enchantments.BLAST_PROTECTION
        )
                || entry.matchesKey(
                Enchantments.THORNS
        )
                || entry.matchesKey(
                Enchantments.DEPTH_STRIDER
        )
                || entry.matchesKey(
                Enchantments.MENDING
        )
                || entry.matchesKey(
                Enchantments.SOUL_SPEED
        );
    }

    /*
     * =========================================================
     * FIND EXACT BOOK
     * =========================================================
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
     * =========================================================
     * INVENTORY -> ANVIL SLOT
     * =========================================================
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

        return 3 + (inventorySlot - 9);
    }

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
     * =========================================================
     * ANVIL SLOT CHECKS
     * =========================================================
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

    private static boolean allAnvilSlotsEmpty(
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
                .isEmpty()

                && handler
                .getSlot(
                        AnvilScreenHandler.OUTPUT_ID
                )
                .getStack()
                .isEmpty();
    }

    /*
     * =========================================================
     * STATE MANAGEMENT
     * =========================================================
     */

    private static void clearSelections() {

        selectedBoot =
                ItemStack.EMPTY;

        selectedBootBook =
                ItemStack.EMPTY;

        selectedFirstBook =
                ItemStack.EMPTY;

        selectedSecondBook =
                ItemStack.EMPTY;
    }

    /*
     * Reset only the current operation.
     *
     * Does NOT change ON/OFF states.
     */

    private static void resetOperationState() {

        lastHandler = null;

        delay = 0;

        stage = Stage.IDLE;

        clearSelections();
    }

    /*
     * Reset ONLY Anvil Plus operation.
     *
     * Enchant Plus remains untouched.
     */

    private static void resetAnvilPlusState() {

        selectedBoot =
                ItemStack.EMPTY;

        selectedBootBook =
                ItemStack.EMPTY;

        if (stage == Stage.BOOT_WAITING_FOR_BOOT
                || stage == Stage.BOOT_WAITING_FOR_BOOK
                || stage == Stage.BOOT_WAITING_FOR_OUTPUT
                || stage == Stage.BOOT_WAITING_FOR_CLEAR) {

            stage = Stage.IDLE;
            delay = ACTION_DELAY_TICKS;
        }
    }

    /*
     * Reset ONLY Enchant Plus operation.
     *
     * Anvil Plus remains untouched.
     */

    private static void resetEnchantPlusState() {

        selectedFirstBook =
                ItemStack.EMPTY;

        selectedSecondBook =
                ItemStack.EMPTY;

        if (stage == Stage.ENCHANT_WAITING_FOR_FIRST
                || stage == Stage.ENCHANT_WAITING_FOR_SECOND
                || stage == Stage.ENCHANT_WAITING_FOR_OUTPUT
                || stage == Stage.ENCHANT_WAITING_FOR_CLEAR) {

            stage = Stage.IDLE;
            delay = ACTION_DELAY_TICKS;
        }
    }

    /*
     * =========================================================
     * COLORED ACTION BAR
     * =========================================================
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
     * =========================================================
     * CONFIG
     * =========================================================
     */

    private static Path configPath() {

        return Path.of(
                "config",
                "anvil_plus.properties"
        );
    }

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
                        "anvilPlusEnabled="
                )) {

                    anvilPlusEnabled =
                            Boolean.parseBoolean(
                                    line.substring(
                                            "anvilPlusEnabled=".length()
                                    )
                            );

                } else if (line.startsWith(
                        "enchantPlusEnabled="
                )) {

                    enchantPlusEnabled =
                            Boolean.parseBoolean(
                                    line.substring(
                                            "enchantPlusEnabled=".length()
                                    )
                            );

                } else if (line.startsWith(
                        "enabled="
                )) {

                    /*
                     * Compatibility with old versions.
                     */
                    anvilPlusEnabled =
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

    private static void saveConfig() {

        Path path =
                configPath();

        try {

            Files.createDirectories(
                    path.getParent()
            );

            String data =
                    "anvilPlusEnabled="
                            + anvilPlusEnabled
                            + System.lineSeparator()

                            + "enchantPlusEnabled="
                            + enchantPlusEnabled
                            + System.lineSeparator();

            Files.writeString(
                    path,
                    data
            );

        } catch (IOException ignored) {
        }
    }
}
