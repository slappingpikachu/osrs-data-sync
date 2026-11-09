/*
 * Copyright (c) 2026, slappingpikachu
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.osrsdatasync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemPrice;

/**
 * Items kept inside other items, or in storage away from the bank. Each store is saved with the time it was last read
 * and how it was read ("how"):
 * <ul>
 * <li>"live": kept in varbits, so it is current whenever the client is logged in;</li>
 * <li>"open": an item container the game only sends while the store is open, so it is as of that time;</li>
 * <li>"check": only the item's Check text lists it, so it is as of the last Check (or "empty" message).</li>
 * </ul>
 * A store only changes when the game reports it. Nothing is guessed.
 */
class ItemStores
{
	static final String LIVE = "live";
	static final String OPEN = "open";
	static final String CHECK = "check";

	/** One store as written to containers.json ("storage" -> key -> this). */
	static class Store
	{
		String name;
		String how;
		long at; // ms since 1970 when the game last reported it
		List<int[]> items = new ArrayList<>();

		Store()
		{
		}

		Store(String name, String how, long at, List<int[]> items)
		{
			this.name = name;
			this.how = how;
			this.at = at;
			this.items = items;
		}
	}

	// ================================================================== "open": item containers

	/** Container id -> {key, name}. */
	private static final Map<Integer, String[]> CONTAINERS = Map.of(
		InventoryID.LOOTING_BAG, new String[]{"lootingBag", "Looting bag"},
		InventoryID.SEED_BOX, new String[]{"seedBox", "Seed box"},
		InventoryID.TACKLE_BOX, new String[]{"tackleBox", "Tackle box"},
		InventoryID.FORESTRY_KIT, new String[]{"forestryKit", "Forestry kit"},
		InventoryID.HUNTSMANS_KIT, new String[]{"huntsmansKit", "Huntsman's kit"},
		InventoryID.PREPOT_DEVICE_INV, new String[]{"chuggingBarrel", "Chugging barrel"},
		InventoryID.SEED_VAULT, new String[]{"seedVault", "Seed vault"},
		// every costume room piece (armour case, cape rack, wardrobe, chests, toy box...) shares this container
		InventoryID.POH_COSTUMES, new String[]{"costumeRoom", "Costume room"});

	// ================================================================== "live": varbits, read from the game's data

	/** Game enum: bolt pouch slot value -> bolt item (used by the pouch's own interface). */
	private static final int ENUM_BOLT_POUCH = 336;
	/** Game enum: tool leprechaun watering can value -> watering can item. */
	private static final int ENUM_WATERING_CANS = 136;

	/** {type varbit, amount varbit} per bolt pouch slot. */
	private static final int[][] BOLT_SLOTS = {
		{VarbitID.XBOWS_POUCH_SLOT1, VarbitID.XBOWS_POUCH_NUM1},
		{VarbitID.XBOWS_POUCH_SLOT2, VarbitID.XBOWS_POUCH_NUM2},
		{VarbitID.XBOWS_POUCH_SLOT3, VarbitID.XBOWS_POUCH_NUM3},
		{VarbitID.XBOWS_POUCH_SLOT4, VarbitID.XBOWS_POUCH_NUM4},
	};

	/** {count varbit, scroll}, as paired by the master scroll book's interface script. Watson is read separately. */
	private static final int[][] SCROLLS = {
		{VarbitID.BOOKOFSCROLLS_NARDAH, ItemID.TELEPORTSCROLL_NARDAH},
		{VarbitID.BOOKOFSCROLLS_DIGSITE, ItemID.TELEPORTSCROLL_DIGSITE},
		{VarbitID.BOOKOFSCROLLS_FELDIP, ItemID.TELEPORTSCROLL_FELDIP},
		{VarbitID.BOOKOFSCROLLS_LUNARISLE, ItemID.TELEPORTSCROLL_LUNARISLE},
		{VarbitID.BOOKOFSCROLLS_MORTTON, ItemID.TELEPORTSCROLL_MORTTON},
		{VarbitID.BOOKOFSCROLLS_PESTCONTROL, ItemID.TELEPORTSCROLL_PESTCONTROL},
		{VarbitID.BOOKOFSCROLLS_PISCATORIS, ItemID.TELEPORTSCROLL_PISCATORIS},
		{VarbitID.BOOKOFSCROLLS_TAIBWO, ItemID.TELEPORTSCROLL_TAIBWO},
		{VarbitID.BOOKOFSCROLLS_ELF, ItemID.TELEPORTSCROLL_ELF},
		{VarbitID.BOOKOFSCROLLS_MOSLES, ItemID.TELEPORTSCROLL_MOSLES},
		{VarbitID.BOOKOFSCROLLS_LUMBERYARD, ItemID.TELEPORTSCROLL_LUMBERYARD},
		{VarbitID.BOOKOFSCROLLS_ZULANDRA, ItemID.TELEPORTSCROLL_ZULANDRA},
		{VarbitID.BOOKOFSCROLLS_CERBERUS, ItemID.TELEPORTSCROLL_CERBERUS},
		{VarbitID.BOOKOFSCROLLS_REVENANTS, ItemID.TELEPORTSCROLL_REVENANTS},
		{VarbitID.BOOKOFSCROLLS_GUTHIXIAN_TEMPLE, ItemID.TELEPORTSCROLL_GUTHIXIAN_TEMPLE},
		{VarbitID.BOOKOFSCROLLS_SPIDERCAVE, ItemID.TELEPORTSCROLL_SPIDERCAVE},
		{VarbitID.BOOKOFSCROLLS_COLOSSAL_WYRM, ItemID.TELEPORTSCROLL_COLOSSAL_WYRM},
		{VarbitID.BOOKOFSCROLLS_CHASMOFFIRE, ItemID.TELEPORTSCROLL_CHASMOFFIRE},
		{VarbitID.BOOKOFSCROLLS_ARDEAGLAIS, ItemID.TELEPORTSCROLL_ARDEAGLAIS},
	};

	/** {count varbit, plank}, as paired by the plank sack's script. */
	private static final int[][] PLANKS = {
		{VarbitID.PLANK_SACK_PLAIN, ItemID.WOODPLANK},
		{VarbitID.PLANK_SACK_OAK, ItemID.PLANK_OAK},
		{VarbitID.PLANK_SACK_TEAK, ItemID.PLANK_TEAK},
		{VarbitID.PLANK_SACK_MAHOGANY, ItemID.PLANK_MAHOGANY},
		{VarbitID.PLANK_SACK_CAMPHOR, ItemID.PLANK_CAMPHOR},
		{VarbitID.PLANK_SACK_IRONWOOD, ItemID.PLANK_IRONWOOD},
		{VarbitID.PLANK_SACK_ROSEWOOD, ItemID.PLANK_ROSEWOOD},
	};

	/** {amount varbit, essence type varbit} per essence pouch. */
	private static final int[][] POUCHES = {
		{VarbitID.SMALL_ESSENCE_POUCH, VarbitID.SMALL_ESSENCE_POUCH_TYPE},
		{VarbitID.MEDIUM_ESSENCE_POUCH, VarbitID.MEDIUM_ESSENCE_POUCH_TYPE},
		{VarbitID.LARGE_ESSENCE_POUCH, VarbitID.LARGE_ESSENCE_POUCH_TYPE},
		{VarbitID.GIANT_ESSENCE_POUCH, VarbitID.GIANT_ESSENCE_POUCH_TYPE},
		{VarbitID.COLOSSAL_ESSENCE_POUCH, VarbitID.COLOSSAL_ESSENCE_POUCH_TYPE},
	};

	/** Varbits the tool leprechaun's store interface reads. */
	private static final int[] LEPRECHAUN = {
		VarbitID.FARMING_TOOLS_RAKE, VarbitID.FARMING_TOOLS_EXTRARAKES,
		VarbitID.FARMING_TOOLS_DIBBER, VarbitID.FARMING_TOOLS_EXTRADIBBERS,
		VarbitID.FARMING_TOOLS_SPADE, VarbitID.FARMING_TOOLS_EXTRASPADES,
		VarbitID.FARMING_TOOLS_SECATEURS, VarbitID.FARMING_TOOLS_EXTRASECATEURS, VarbitID.FARMING_TOOLS_FAIRYSECATEURS,
		VarbitID.FARMING_TOOLS_TROWEL, VarbitID.FARMING_TOOLS_EXTRATROWELS,
		VarbitID.FARMING_TOOLS_WATERINGCAN, VarbitID.FARMING_TOOLS_PLANTCURE,
		VarbitID.FARMING_TOOLS_BUCKETS, VarbitID.FARMING_TOOLS_EXTRABUCKETS, VarbitID.FARMING_TOOLS_EXTRA2BUCKETS,
		VarbitID.FARMING_TOOLS_COMPOST, VarbitID.FARMING_TOOLS_EXTRACOMPOST,
		VarbitID.FARMING_TOOLS_SUPERCOMPOST, VarbitID.FARMING_TOOLS_EXTRASUPERCOMPOST,
		VarbitID.FARMING_TOOLS_ULTRACOMPOST, VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE,
	};

	private static final Set<Integer> LIVE_VARPS = Set.of(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO,
		VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT);
	private static final Set<Integer> LIVE_VARBITS = new HashSet<>();

	static
	{
		for (int[][] table : new int[][][]{BOLT_SLOTS, SCROLLS, PLANKS, POUCHES})
		{
			for (int[] row : table)
			{
				LIVE_VARBITS.add(row[0]);
				if (table == BOLT_SLOTS || table == POUCHES)
				{
					LIVE_VARBITS.add(row[1]); // the second column of these is a varbit too
				}
			}
		}
		for (int v : LEPRECHAUN)
		{
			LIVE_VARBITS.add(v);
		}
		LIVE_VARBITS.add(VarbitID.BOOKOFSCROLLS_WATSON_HIGHBITS);
		LIVE_VARBITS.add(VarbitID.BOOKOFSCROLLS_WATSON_LOWBITS);
	}

	// ================================================================== "check": what the item's Check text says

	/** A store that is only known from its Check text. */
	private static final class Checked
	{
		final String key;
		final String name;
		final Pattern mentioned; // how the game's text refers to it
		final Set<Integer> items; // the item, in every form (open, closed, upgraded)
		final String[] prefixes; // tried before an entry's name when looking it up ("Uncut " for gems)

		Checked(String key, String name, String mentioned, Set<Integer> items, String... prefixes)
		{
			this.key = key;
			this.name = name;
			this.mentioned = Pattern.compile("\\b(?:" + mentioned + ")\\b", Pattern.CASE_INSENSITIVE);
			this.items = items;
			this.prefixes = prefixes;
		}
	}

	private static final List<Checked> CHECKED = List.of(
		new Checked("coalBag", "Coal bag", "coal bag",
			Set.of(ItemID.COAL_BAG, ItemID.COAL_BAG_OPEN)),
		new Checked("gemBag", "Gem bag", "gem bag|gem sack",
			Set.of(ItemID.GEM_BAG, ItemID.GEM_BAG_OPEN, ItemID.GEM_SACK, ItemID.GEM_SACK_OPEN), "Uncut "),
		new Checked("herbSack", "Herb sack", "herb sack",
			Set.of(ItemID.SLAYER_HERB_SACK, ItemID.SLAYER_HERB_SACK_OPEN, ItemID.SLAYER_HERB_SACK_SILK,
				ItemID.SLAYER_HERB_SACK_SILK_OPEN)),
		new Checked("logBasket", "Log basket", "basket",
			Set.of(ItemID.LOG_BASKET_CLOSED, ItemID.LOG_BASKET_OPEN, ItemID.FORESTRY_BASKET_CLOSED,
				ItemID.FORESTRY_BASKET_OPEN)),
		new Checked("fishBarrel", "Fish barrel", "(?<!chugging )barrel",
			Set.of(ItemID.FISH_BARREL_CLOSED, ItemID.FISH_BARREL_OPEN, ItemID.FISH_SACK_BARREL_CLOSED,
				ItemID.FISH_SACK_BARREL_OPEN), "Raw "));

	private static final String COUNT = "(\\d[\\d,]*|one)";
	private static final String NAME = "([A-Za-z][A-Za-z' ()-]*[A-Za-z)])";
	/** One entry of a contents list, in any of the forms "12 x Yew logs", "Sapphires: 3" or "27 pieces of coal". */
	private static final Pattern ENTRY = Pattern.compile(
		COUNT + "\\s*[x\u00d7]\\s+" + NAME
			+ "|([A-Za-z][A-Za-z' ()-]*?):\\s*(\\d[\\d,]*)(?![\\d,]|\\s*[x×]\\s)"
			+ "|" + COUNT + " pieces? of " + NAME,
		Pattern.CASE_INSENSITIVE);
	private static final Pattern EMPTY = Pattern.compile(
		"\\bis (?:now |already )?empty\\b|\\bempt(?:y|ied)\\b.*\\binto (?:the|your) bank\\b", Pattern.CASE_INSENSITIVE);
	/** The bank's button that empties every carried container at once. */
	private static final Pattern EMPTY_ALL = Pattern.compile(
		"\\bempt(?:y|ied)\\b.*\\bcontainers\\b.*\\bbank\\b", Pattern.CASE_INSENSITIVE);

	// ================================================================== state

	private final Client client;
	private final ItemManager itemManager;
	private final Map<String, Store> stores = new TreeMap<>();
	private final Map<String, Integer> nameCache = new HashMap<>();
	private boolean liveDue = true;
	private boolean potionsDue = true;
	private boolean potionWidgetSeen;
	private Checked listing; // a Check whose entries come on the following chat lines
	private Map<Integer, Integer> listed;
	private String lastBoxText;
	private boolean changed;

	ItemStores(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	void reset()
	{
		stores.clear();
		liveDue = potionsDue = true;
		potionWidgetSeen = false;
		listing = null;
		listed = null;
		lastBoxText = null;
		changed = false;
	}

	/** Stores saved last session that weren't read again yet this session. */
	void restore(Map<String, Store> saved)
	{
		if (saved == null)
		{
			return;
		}
		saved.forEach((key, s) ->
		{
			if (key != null && s != null && s.items != null)
			{
				stores.putIfAbsent(key, s);
			}
		});
	}

	Map<String, Store> all()
	{
		return stores;
	}

	/** True once after a store changed (so containers.json gets written). */
	boolean takeChanged()
	{
		boolean c = changed;
		changed = false;
		return c;
	}

	private void put(String key, String name, String how, List<int[]> items)
	{
		Store old = stores.get(key);
		if (old != null && LIVE.equals(how) && how.equals(old.how) && sameItems(old.items, items))
		{
			return; // a live store read again without any difference keeps its time
		}
		stores.put(key, new Store(name, how, System.currentTimeMillis(), items));
		changed = true;
	}

	private static boolean sameItems(List<int[]> a, List<int[]> b)
	{
		if (a.size() != b.size())
		{
			return false;
		}
		for (int i = 0; i < a.size(); i++)
		{
			if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1])
			{
				return false;
			}
		}
		return true;
	}

	// ================================================================== events (client thread)

	void onItemContainerChanged(int id, ItemContainer container)
	{
		if (id == InventoryID.POTION_STORE_TEMP_INV || id == InventoryID.INV || id == InventoryID.BANK)
		{
			potionsDue = true; // potions may have gone into or out of potion storage
		}
		String[] store = CONTAINERS.get(id >= 0x8000 ? id - 0x8000 : id); // some houses send the costume room offset
		if (store != null)
		{
			put(store[0], store[1], OPEN, toList(stack(container)));
		}
	}

	void onVarChanged(int varp, int varbit)
	{
		if (LIVE_VARPS.contains(varp) || (varbit != -1 && LIVE_VARBITS.contains(varbit)))
		{
			liveDue = true;
		}
	}

	void onChatMessage(String message)
	{
		String text = message.replaceAll("<[^>]+>", " ").trim();
		if (listing != null)
		{
			int result = readEntries(text, listing, listed);
			if (result == READ)
			{
				return; // another line of the list
			}
			if (result == UNKNOWN)
			{
				listing = null; // an item we can't place: drop this Check rather than save part of it
				listed = null;
				return;
			}
			finishListing();
		}

		if (EMPTY_ALL.matcher(text).find())
		{
			for (Checked c : CHECKED)
			{
				if (carrying(c))
				{
					put(c.key, c.name, CHECK, new ArrayList<>());
				}
			}
			return;
		}

		for (Checked c : CHECKED)
		{
			if (c.mentioned.matcher(text).find() && carrying(c))
			{
				readCheck(c, text, true);
				return;
			}
		}
	}

	void onGameTick()
	{
		if (listing != null)
		{
			finishListing(); // every line of a Check arrives in the same tick
		}
		if (liveDue)
		{
			liveDue = false;
			readLive();
		}
		readCheckBox();
		readPotionStorage();
	}

	// ================================================================== Check text

	/**
	 * Reads one Check text about {@code c}: its entries, or that it is empty. A text that ends in ":" with no
	 * entries starts a list that continues on the next chat lines (when {@code mayList}).
	 */
	private void readCheck(Checked c, String text, boolean mayList)
	{
		Map<Integer, Integer> items = new LinkedHashMap<>();
		int result = readEntries(text, c, items);
		if (result == READ)
		{
			put(c.key, c.name, CHECK, toList(items));
		}
		else if (result == UNKNOWN)
		{
			return; // keep the last good copy
		}
		else if (EMPTY.matcher(text).find())
		{
			put(c.key, c.name, CHECK, new ArrayList<>());
		}
		else if (mayList && text.endsWith(":"))
		{
			listing = c;
			listed = new LinkedHashMap<>();
		}
	}

	private static final int NONE = 0;
	private static final int READ = 1;
	private static final int UNKNOWN = -1;

	/**
	 * Adds the entries in {@code text} to {@code into}: READ when it has entries and all were recognised, NONE when
	 * it has none, UNKNOWN (adding nothing) when one wasn't, so a partly read Check never replaces a good one.
	 */
	private int readEntries(String text, Checked c, Map<Integer, Integer> into)
	{
		Map<Integer, Integer> found = new LinkedHashMap<>();
		boolean any = false;
		Matcher m = ENTRY.matcher(text);
		while (m.find())
		{
			String count = m.group(1) != null ? m.group(1) : m.group(4) != null ? m.group(4) : m.group(5);
			String name = m.group(1) != null ? m.group(2) : m.group(4) != null ? m.group(3) : m.group(6);
			int n = count.equalsIgnoreCase("one") ? 1 : Integer.parseInt(count.replace(",", ""));
			int id = itemByName(name.trim(), c.prefixes);
			if (id <= 0)
			{
				if (n > 0)
				{
					return UNKNOWN;
				}
				continue; // "Opal: 0" for a gem the lookup doesn't know does no harm
			}
			any = true;
			if (n > 0)
			{
				found.merge(id, n, Integer::sum);
			}
		}
		if (!any)
		{
			return NONE;
		}
		found.forEach((id, n) -> into.merge(id, n, Integer::sum));
		return READ;
	}

	private void finishListing()
	{
		put(listing.key, listing.name, CHECK, toList(listed));
		listing = null;
		listed = null;
	}

	/** The item box a log basket or fish barrel opens on Check. */
	private void readCheckBox()
	{
		Widget box = client.getWidget(InterfaceID.Objectbox.TEXT);
		String text = box == null || box.isHidden() ? null : box.getText();
		if (text == null || text.equals(lastBoxText))
		{
			lastBoxText = text;
			return;
		}
		lastBoxText = text;
		String plain = text.replace("<br>", "\n").replaceAll("<[^>]+>", "").trim();
		for (Checked c : CHECKED)
		{
			if (c.mentioned.matcher(plain).find() && carrying(c))
			{
				readCheck(c, plain, false);
				return;
			}
		}
	}

	/** Whether any form of the store's item is in the inventory (Check and Empty both need it there). */
	private boolean carrying(Checked c)
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv == null)
		{
			return false;
		}
		for (Item item : inv.getItems())
		{
			if (c.items.contains(item.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/** Item id for a name in Check text: the name as given, with the store's prefixes, singular or plural. */
	private int itemByName(String name, String[] prefixes)
	{
		String singular = name.endsWith("ies") ? name.substring(0, name.length() - 3) + "y"
			: name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
		for (String prefix : prefixes)
		{
			for (String n : new String[]{prefix + name, prefix + singular})
			{
				int id = exactItem(n);
				if (id > 0)
				{
					return id;
				}
			}
		}
		int id = exactItem(name);
		return id > 0 ? id : exactItem(singular);
	}

	private int exactItem(String name)
	{
		String key = name.toLowerCase(Locale.ROOT);
		Integer cached = nameCache.get(key);
		if (cached != null)
		{
			return cached;
		}
		int id = -1;
		for (ItemPrice p : itemManager.search(name))
		{
			if (p.getName().equalsIgnoreCase(name))
			{
				id = p.getId();
				break;
			}
		}
		nameCache.put(key, id);
		return id;
	}

	// ================================================================== live stores

	private int vb(int varbit)
	{
		return client.getVarbitValue(varbit);
	}

	private void readLive()
	{
		Map<Integer, Integer> bolts = new LinkedHashMap<>();
		EnumComposition boltEnum = client.getEnum(ENUM_BOLT_POUCH);
		for (int[] slot : BOLT_SLOTS)
		{
			int type = vb(slot[0]);
			if (type > 0 && boltEnum != null)
			{
				add(bolts, boltEnum.getIntValue(type), vb(slot[1]));
			}
		}
		put("boltPouch", "Bolt pouch", LIVE, toList(bolts));

		Map<Integer, Integer> scrolls = new LinkedHashMap<>();
		for (int[] s : SCROLLS)
		{
			add(scrolls, s[1], vb(s[0]));
		}
		add(scrolls, ItemID.TELEPORTSCROLL_WATSON,
			vb(VarbitID.BOOKOFSCROLLS_WATSON_HIGHBITS) * 256 + vb(VarbitID.BOOKOFSCROLLS_WATSON_LOWBITS));
		put("scrollBook", "Master scroll book", LIVE, toList(scrolls));

		Map<Integer, Integer> planks = new LinkedHashMap<>();
		for (int[] p : PLANKS)
		{
			add(planks, p[1], vb(p[0]));
		}
		put("plankSack", "Plank sack", LIVE, toList(planks));

		Map<Integer, Integer> essence = new LinkedHashMap<>();
		for (int[] pouch : POUCHES)
		{
			add(essence, essenceOfType(vb(pouch[1])), vb(pouch[0]));
		}
		put("essencePouches", "Essence pouches", LIVE, toList(essence));

		Map<Integer, Integer> quiver = new LinkedHashMap<>();
		int ammo = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
		if (ammo > 0)
		{
			add(quiver, itemManager.canonicalize(ammo), client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT));
		}
		put("quiver", "Dizana's quiver", LIVE, toList(quiver));

		put("leprechaun", "Tool leprechaun", LIVE, toList(leprechaun()));
	}

	/** Essence pouch type value -> essence, as the game's pouch script checks them. */
	private static int essenceOfType(int type)
	{
		switch (type)
		{
			case 1:
				return ItemID.BLANKRUNE;
			case 2:
				return ItemID.BLANKRUNE_HIGH;
			case 3:
				return ItemID.BLANKRUNE_DAEYALT;
			case 4:
				return ItemID.GOTR_GUARDIAN_ESSENCE;
			default:
				return -1;
		}
	}

	/** The tool leprechaun's store, counted the way its interface script counts each tool. */
	private Map<Integer, Integer> leprechaun()
	{
		Map<Integer, Integer> tools = new LinkedHashMap<>();
		add(tools, ItemID.RAKE, vb(VarbitID.FARMING_TOOLS_RAKE) + 2 * vb(VarbitID.FARMING_TOOLS_EXTRARAKES));
		add(tools, ItemID.DIBBER, vb(VarbitID.FARMING_TOOLS_DIBBER) + 2 * vb(VarbitID.FARMING_TOOLS_EXTRADIBBERS));
		add(tools, ItemID.SPADE, vb(VarbitID.FARMING_TOOLS_SPADE) + 2 * vb(VarbitID.FARMING_TOOLS_EXTRASPADES));
		add(tools, vb(VarbitID.FARMING_TOOLS_FAIRYSECATEURS) == 1 ? ItemID.FAIRY_ENCHANTED_SECATEURS : ItemID.SECATEURS,
			vb(VarbitID.FARMING_TOOLS_SECATEURS) + 2 * vb(VarbitID.FARMING_TOOLS_EXTRASECATEURS));
		add(tools, ItemID.GARDENING_TROWEL, vb(VarbitID.FARMING_TOOLS_TROWEL) + 2 * vb(VarbitID.FARMING_TOOLS_EXTRATROWELS));

		int can = vb(VarbitID.FARMING_TOOLS_WATERINGCAN);
		EnumComposition cans = client.getEnum(ENUM_WATERING_CANS);
		if (can > 0 && cans != null)
		{
			add(tools, cans.getIntValue(can), 1);
		}
		add(tools, ItemID.PLANT_CURE, vb(VarbitID.FARMING_TOOLS_PLANTCURE));

		int bottomless = vb(VarbitID.FARMING_TOOLS_BOTTOMLESS_BUCKET_TYPE);
		if (bottomless > 0)
		{
			add(tools, bottomless == 1 ? ItemID.BOTTOMLESS_COMPOST_BUCKET : ItemID.BOTTOMLESS_COMPOST_BUCKET_FILLED, 1);
		}
		add(tools, ItemID.BUCKET_EMPTY, vb(VarbitID.FARMING_TOOLS_BUCKETS)
			+ 32 * vb(VarbitID.FARMING_TOOLS_EXTRABUCKETS) + 256 * vb(VarbitID.FARMING_TOOLS_EXTRA2BUCKETS));
		add(tools, ItemID.BUCKET_COMPOST, vb(VarbitID.FARMING_TOOLS_COMPOST) + 256 * vb(VarbitID.FARMING_TOOLS_EXTRACOMPOST));
		add(tools, ItemID.BUCKET_SUPERCOMPOST,
			vb(VarbitID.FARMING_TOOLS_SUPERCOMPOST) + 256 * vb(VarbitID.FARMING_TOOLS_EXTRASUPERCOMPOST));
		add(tools, ItemID.BUCKET_ULTRACOMPOST, vb(VarbitID.FARMING_TOOLS_ULTRACOMPOST));
		return tools;
	}

	// ================================================================== potion storage

	/**
	 * Potion storage (the bank's potion tab) is not part of the bank container. Its doses come from the game's own
	 * script while the bank is loaded, and are saved as whole potions: 4-dose first, any rest as one smaller potion.
	 */
	private void readPotionStorage()
	{
		Widget widget = client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
		if (widget == null)
		{
			potionWidgetSeen = false;
			return;
		}
		if (!potionWidgetSeen)
		{
			potionWidgetSeen = true;
			potionsDue = true;
		}
		if (!potionsDue)
		{
			return;
		}
		potionsDue = false;
		Map<Integer, Integer> potions = new LinkedHashMap<>();
		add(potions, ItemID.VIAL_EMPTY, client.getVarpValue(VarPlayerID.POTIONSTORE_VIALS));
		for (int listEnum : new int[]{EnumID.POTIONSTORE_POTIONS, EnumID.POTIONSTORE_UNFINISHED_POTIONS})
		{
			EnumComposition list = client.getEnum(listEnum);
			if (list == null || list.getIntVals() == null)
			{
				continue;
			}
			for (int potionEnum : list.getIntVals())
			{
				EnumComposition byDose = client.getEnum(potionEnum);
				if (byDose == null)
				{
					continue;
				}
				client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnum);
				int doses = client.getIntStack()[0];
				for (int dose = 4; dose >= 1 && doses > 0; dose--)
				{
					int id = byDose.getIntValue(dose);
					if (id > 0 && doses >= dose)
					{
						add(potions, id, doses / dose);
						doses %= dose;
					}
				}
			}
		}
		put("potionStorage", "Potion storage", OPEN, toList(potions));
	}

	// ================================================================== helpers

	private static void add(Map<Integer, Integer> items, int id, int n)
	{
		if (id > 0 && n > 0)
		{
			items.merge(id, n, Integer::sum);
		}
	}

	private Map<Integer, Integer> stack(ItemContainer container)
	{
		Map<Integer, Integer> items = new LinkedHashMap<>();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item.getId() > 0)
				{
					add(items, itemManager.canonicalize(item.getId()), item.getQuantity());
				}
			}
		}
		return items;
	}

	private static List<int[]> toList(Map<Integer, Integer> items)
	{
		List<int[]> list = new ArrayList<>();
		items.forEach((id, n) -> list.add(new int[]{id, n}));
		return list;
	}
}
