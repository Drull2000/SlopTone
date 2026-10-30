package ru.baritoneru;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Turns user words ("алмазы", "diamond", "minecraft:iron_ore", "дерево") into block sets. */
public final class BlockNames {
    private static final Map<String, Supplier<Set<Block>>> WORDS = new HashMap<>();

    private static void add(Supplier<Set<Block>> s, String... words) {
        for (String w : words) WORDS.put(w, s);
    }

    private static Supplier<Set<Block>> of(Block... blocks) {
        return () -> new LinkedHashSet<>(java.util.Arrays.asList(blocks));
    }

    static {
        add(of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), "алмаз", "алмазы", "алмазная_руда", "алмазов", "diamond", "diamonds");
        add(of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), "железо", "железная_руда", "железа", "iron");
        add(of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE), "золото", "золотая_руда", "золота", "gold");
        add(of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE), "уголь", "угольная_руда", "угля", "coal");
        add(of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE), "медь", "медная_руда", "меди", "copper");
        add(of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE), "изумруд", "изумруды", "изумрудная_руда", "emerald", "emeralds");
        add(of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE), "редстоун", "редстон", "красная_пыль", "redstone");
        add(of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE), "лазурит", "лазурь", "lapis");
        add(of(Blocks.NETHER_QUARTZ_ORE), "кварц", "кварцевая_руда", "quartz");
        add(of(Blocks.ANCIENT_DEBRIS), "незерит", "обломки", "древние_обломки", "netherite", "debris");
        add(of(Blocks.STONE), "камень");
        add(of(Blocks.COBBLESTONE), "булыжник");
        add(of(Blocks.DEEPSLATE), "сланец", "глубинный_сланец");
        add(of(Blocks.DIRT, Blocks.GRASS_BLOCK), "земля", "дёрн", "дерн");
        add(of(Blocks.SAND, Blocks.RED_SAND), "песок");
        add(of(Blocks.GRAVEL), "гравий");
        add(of(Blocks.CLAY), "глина");
        add(of(Blocks.OBSIDIAN), "обсидиан");
        add(of(Blocks.NETHERRACK), "незерак", "адский_камень");
        add(of(Blocks.GLOWSTONE), "светокамень", "светящийся_камень");
        add(of(Blocks.PUMPKIN), "тыква", "тыквы");
        add(of(Blocks.MELON), "арбуз", "арбузы");
        add(of(Blocks.SUGAR_CANE), "тростник");
        add(of(Blocks.CHEST, Blocks.TRAPPED_CHEST), "сундук", "сундуки");
        add(of(Blocks.SPAWNER), "спавнер");
        add(of(Blocks.CRAFTING_TABLE), "верстак");
        add(of(Blocks.FURNACE), "печь", "печка");
        add(of(Blocks.AMETHYST_CLUSTER, Blocks.BUDDING_AMETHYST), "аметист");
        add(of(Blocks.ICE, Blocks.PACKED_ICE, Blocks.BLUE_ICE), "лёд", "лед");
        add(of(Blocks.SNOW_BLOCK), "снег");
        add(of(Blocks.NETHER_PORTAL), "портал");
        add(of(Blocks.END_PORTAL_FRAME), "энд_портал", "рамка_портала");
        add(() -> tagged(BlockTags.BEDS), "кровать", "кровати", "bed");
        add(() -> tagged(BlockTags.LOGS), "дерево", "деревья", "бревно", "бревна", "брёвна", "древесина", "log", "logs", "wood");
        // wood by species; "ё" is normalised to "е" before lookup, so only the "е" spelling is needed
        wood(Blocks.OAK_LOG, Blocks.OAK_WOOD, "дуб", "дубовое", "дубовая", "дубовые", "oak");
        wood(Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD, "береза", "березы", "березовое", "березовая", "березовые", "birch");
        wood(Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, "ель", "елка", "ели", "еловое", "еловая", "еловые", "spruce");
        wood(Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD, "тропическое", "тропическая", "тропические", "джунгли", "jungle");
        wood(Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD, "акация", "акации", "акациевое", "акациевая", "acacia");
        wood(Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD, "темный_дуб", "темного_дуба", "темное_дубовое", "темная_дубовая", "dark_oak");
        wood(Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_WOOD, "бледный_дуб", "бледного_дуба", "бледное_дубовое", "бледная_дубовая", "pale_oak");
        wood(Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD, "вишня", "вишни", "вишневое", "вишневая", "сакура", "cherry");
        wood(Blocks.MANGROVE_LOG, Blocks.MANGROVE_WOOD, "мангр", "мангровое", "мангровая", "мангровые", "mangrove");
        wood(Blocks.POPLAR_LOG, Blocks.POPLAR_WOOD, "тополь", "тополя", "тополиное", "тополиная", "poplar");
        add(of(Blocks.CRIMSON_STEM), "багровый_стебель", "багровые_стебли", "багровое_дерево", "crimson");
        add(of(Blocks.WARPED_STEM), "искаженный_стебель", "искаженные_стебли", "искаженное_дерево", "warped");
        add(() -> tagged(BlockTags.LEAVES), "листва", "листья", "leaves");
        add(() -> tagged(BlockTags.PLANKS), "доски", "planks");
        add(() -> tagged(BlockTags.WOOL), "шерсть", "wool");
    }

    /**
     * Registers one tree species. Each word also works followed by бревно/бревна/древесина/дерево,
     * e.g. "береза", "березовое_бревно", "березовая_древесина", "березовые_бревна".
     */
    private static void wood(Block log, Block wood, String... words) {
        Supplier<Set<Block>> s = of(log, wood);
        for (String w : words) {
            add(s, w);
            for (String suffix : new String[]{"_бревно", "_бревна", "_древесина", "_дерево", "_деревья", "_log", "_logs", "_wood"})
                add(s, w + suffix);
        }
    }

    private static Set<Block> tagged(net.minecraft.tags.TagKey<Block> tag) {
        Set<Block> out = new LinkedHashSet<>();
        for (Block b : BuiltInRegistries.BLOCK) if (b.defaultBlockState().is(tag)) out.add(b);
        return out;
    }

    /** null if the word means nothing. */
    public static Set<Block> resolve(String word) {
        String w = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
        Supplier<Set<Block>> s = WORDS.get(w);
        if (s == null) s = WORDS.get(word.toLowerCase(Locale.ROOT));
        if (s != null) return s.get();
        Identifier id = Identifier.tryParse(w.contains(":") ? w : "minecraft:" + w);
        if (id == null) return null;
        var opt = BuiltInRegistries.BLOCK.getOptional(id);
        if (opt.isEmpty() || opt.get() == Blocks.AIR) return null;
        Set<Block> out = new LinkedHashSet<>();
        out.add(opt.get());
        // "iron_ore" should also find "deepslate_iron_ore"
        if (id.getPath().endsWith("_ore") && !id.getPath().startsWith("deepslate_")) {
            BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath(id.getNamespace(), "deepslate_" + id.getPath())).ifPresent(out::add);
        }
        return out;
    }

    public static String describe(Set<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Block b : blocks) {
            if (n++ > 0) sb.append(", ");
            if (n > 3) {
                sb.append("…");
                break;
            }
            sb.append(BuiltInRegistries.BLOCK.getKey(b).getPath());
        }
        return sb.toString();
    }
}
