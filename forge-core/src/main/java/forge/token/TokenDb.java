package forge.token;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;

import forge.card.CardDb;
import forge.card.CardEdition;
import forge.card.CardRules;
import forge.item.IPaperCard;
import forge.item.PaperToken;
import forge.util.Aggregates;

import java.util.*;
import java.util.function.Predicate;

public class TokenDb implements ITokenDatabase {
    // Expected naming convention of scripts
    // token_name
    // minor_demon
    // marit_lage
    // gold

    // colors_power_toughness_cardtypes_sub_types_keywords
    // Some examples:
    // c_3_3_a_phyrexian_wurm_lifelink
    // w_2_2_knight_first_strike

    // The image names should be the same as the script name + _set
    // If that isn't found, consider falling back to the original token
    // Thread-safe: this single TokenDb (StaticData.allTokens) is shared across all
    // concurrent games in one JVM (Endstep) and both maps are lazily populated DURING
    // gameplay (getToken -> loadTokenFromSet / fallback, on every token creation;
    // preloadTokens() is GUI-only so headless never preloads). Wrap so concurrent put
    // can't corrupt the backing maps. EVERY iteration of an allTokenByName view
    // (getToken, fallbackToken, getAllTokens, iterator) holds synchronized(allTokenByName)
    // and the lazy load is double-checked under it, per Guava's synchronizedMultimap contract.
    private final Multimap<String, PaperToken> allTokenByName =
            com.google.common.collect.Multimaps.synchronizedMultimap(HashMultimap.create());
    private final Map<String, PaperToken> extraTokensByName =
            java.util.Collections.synchronizedMap(Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER));

    private final CardEdition.Collection editions;
    private final Map<String, CardRules> rulesByName;

    // null preserves first-alphabetical match; adventure pushes a filter here.
    private Predicate<CardEdition> defaultEditionFilter = null;
    // Blocklist of "{EDITION_CODE}/{tokenScript}" pairs; skipped in fallback.
    private Set<String> restrictedTokenEntries = Collections.emptySet();
    // When true and a host-card date is known, pick the legal edition whose
    // release date is closest to the host's, so eras match (e.g. a 1999 card
    // gets a 1998 Unglued token rather than a 2002 Player Rewards print).
    private boolean preferEraMatchedArt = false;

    public TokenDb(Map<String, CardRules> rules, CardEdition.Collection editions) {
        this.rulesByName = rules;
        this.editions = editions;
    }

    public void setDefaultEditionFilter(Predicate<CardEdition> filter) {
        this.defaultEditionFilter = filter;
    }

    public void setRestrictedTokenEntries(Set<String> entries) {
        this.restrictedTokenEntries = entries != null ? entries : Collections.emptySet();
    }

    public void setPreferEraMatchedArt(boolean flag) {
        this.preferEraMatchedArt = flag;
    }

    public boolean containsRule(String rule) {
        return this.rulesByName.containsKey(rule);
    }

    public void preloadTokens() {
        for (CardEdition edition : this.editions) {
            for (Map.Entry<String, Collection<CardEdition.EditionEntry>> inSet : edition.getTokens().asMap().entrySet()) {
                String name = inSet.getKey();
                String fullName = String.format("%s_%s", name, edition.getCode().toLowerCase());
                for (CardEdition.EditionEntry t : inSet.getValue()) {
                    allTokenByName.put(fullName, addTokenInSet(edition, name, t));
                }
            }
        }
    }

    protected boolean loadTokenFromSet(CardEdition edition, String name) {
        String fullName = String.format("%s_%s", name, edition.getCode().toLowerCase());
        if (allTokenByName.containsKey(fullName)) {
            return true;
        }
        if (!edition.getTokens().containsKey(name)) {
            return false;
        }

        // Double-checked under the lock so concurrent games first-loading the same
        // token don't both run the put loop (PaperToken has identity equality, so a
        // racing pair would leave duplicate entries) and so this can't interleave
        // with a view iteration (getToken/fallbackToken) on another thread.
        synchronized (allTokenByName) {
            if (allTokenByName.containsKey(fullName)) {
                return true;
            }
            for (CardEdition.EditionEntry t : edition.getTokens().get(name)) {
                allTokenByName.put(fullName, addTokenInSet(edition, name, t));
            }
        }
        return true;
    }

    protected PaperToken addTokenInSet(CardEdition edition, String name, CardEdition.EditionEntry t) {
        CardRules rules;
        if (rulesByName.containsKey(name)) {
            rules = rulesByName.get(name);
        } else if ("w_2_2_spirit".equals(name) || "w_3_3_spirit".equals(name)) { // Hotfix for Endure Token
            rules = rulesByName.get("w_x_x_spirit");
        } else {
            throw new RuntimeException("wrong token name:" + name);
        }
        return new PaperToken(rules, edition, name, t.collectorNumber(), t.artistName());
    }

    // Null filter: historical first-alphabetical match. Non-null: random among
    // editions that register the token and pass the filter, or null if none.
    // When preferEraMatchedArt is on and hostDate != null, instead picks the
    // legal edition whose release date is closest to hostDate.
    public PaperToken getTokenFromEditions(String tokenName, Predicate<CardEdition> editionFilter, Date hostDate) {
        if (editionFilter == null) {
            for (CardEdition edition : this.editions) {
                if (restrictedTokenEntries.contains(edition.getCode() + "/" + tokenName)) continue;
                String fullName = String.format("%s_%s", tokenName, edition.getCode().toLowerCase());
                if (loadTokenFromSet(edition, tokenName)) {
                    // Snapshot under the lock: allTokenByName.get(..) is a live view of a
                    // synchronizedMultimap and Aggregates.random iterates it (Guava requires
                    // manual sync on view iteration).
                    List<PaperToken> collection;
                    synchronized (allTokenByName) {
                        collection = new ArrayList<>(allTokenByName.get(fullName));
                    }
                    return Aggregates.random(collection);
                }
            }
            return null;
        }
        List<CardEdition> legal = new ArrayList<>();
        for (CardEdition edition : this.editions) {
            if (!loadTokenFromSet(edition, tokenName)) continue;
            if (restrictedTokenEntries.contains(edition.getCode() + "/" + tokenName)) continue;
            if (editionFilter.test(edition)) legal.add(edition);
        }
        if (legal.isEmpty()) return null;
        CardEdition pick;
        if (preferEraMatchedArt && hostDate != null) {
            pick = legal.get(0);
            long best = Math.abs(pick.getDate().getTime() - hostDate.getTime());
            for (int i = 1; i < legal.size(); i++) {
                long delta = Math.abs(legal.get(i).getDate().getTime() - hostDate.getTime());
                if (delta < best) {
                    best = delta;
                    pick = legal.get(i);
                }
            }
        } else {
            pick = Aggregates.random(legal);
        }
        String fullName = String.format("%s_%s", tokenName, pick.getCode().toLowerCase());
        // Snapshot under the lock: allTokenByName.get(..) is a live view of a
        // synchronizedMultimap and Aggregates.random iterates it (Guava requires
        // manual sync on view iteration).
        List<PaperToken> collection;
        synchronized (allTokenByName) {
            collection = new ArrayList<>(allTokenByName.get(fullName));
        }
        return Aggregates.random(collection);
    }

    protected PaperToken fallbackToken(String name, String hostEditionCode) {
        Date hostDate = null;
        if (hostEditionCode != null) {
            CardEdition host = this.editions.get(hostEditionCode);
            if (host != null) hostDate = host.getDate();
        }
        return getTokenFromEditions(name, defaultEditionFilter, hostDate);
    }

    @Override
    public PaperToken getToken(String tokenName) {
        return getToken(tokenName, CardEdition.UNKNOWN.getCode());
    }

    @Override
    public PaperToken getToken(String tokenName, String edition) {
        return getToken(tokenName, edition, -1);
    }

    @Override
    public PaperToken getToken(String tokenName, String edition, int artIndex) {
        CardEdition realEdition = editions.getEditionByCodeOrThrow(edition);
        String fullName = String.format("%s_%s", tokenName, realEdition.getCode().toLowerCase());

        // Token exists in edition, return token at artIndex or a random one.
        if (loadTokenFromSet(realEdition, tokenName)) {
            // Snapshot under the lock: allTokenByName.get(..) is a live view of a
            // synchronizedMultimap and the reads below iterate it (Guava requires
            // manual sync on view iteration).
            List<PaperToken> collection;
            synchronized (allTokenByName) {
                collection = new ArrayList<>(allTokenByName.get(fullName));
            }

            if (artIndex < 1 || artIndex > collection.size()) {
                return Aggregates.random(collection);
            }

            return Iterables.get(collection, artIndex - 1);
        }
        PaperToken fallback = this.fallbackToken(tokenName, edition);
        if (fallback != null) {
            return fallback;
        }

        CardRules cr = rulesByName.get(tokenName);
        if (!extraTokensByName.containsKey(fullName) && cr != null) {
            try {
                PaperToken pt = new PaperToken(cr, realEdition, tokenName, "", IPaperCard.NO_ARTIST_NAME);
                extraTokensByName.put(fullName, pt);
                return pt;
            } catch(Exception e) {
                throw e;
            }
        }

        return extraTokensByName.get(fullName);
    }

    @Override
    public PaperToken getTokenFromEditions(String tokenName, CardDb.CardArtPreference fromSet) {
        return null;
    }

    @Override
    public PaperToken getTokenFromEditions(String tokenName, Date printedBefore, CardDb.CardArtPreference fromSet) {
        return null;
    }

    @Override
    public PaperToken getTokenFromEditions(String tokenName, Date printedBefore, CardDb.CardArtPreference fromSet, int artIndex) {
        return null;
    }

    @Override
    public PaperToken getFoiled(PaperToken cpi) {
        return null;
    }

    @Override
    public int getPrintCount(String cardName, String edition) {
        return 0;
    }

    @Override
    public int getMaxPrintCount(String cardName) {
        return 0;
    }

    @Override
    public int getArtCount(String cardName, String edition) {
        return 0;
    }

    @Override
    public Collection<PaperToken> getUniqueTokens() {
        return null;
    }

    @Override
    public List<PaperToken> getAllTokens() {
        synchronized (allTokenByName) {
            return new ArrayList<>(allTokenByName.values());
        }
    }

    @Override
    public List<PaperToken> getAllTokens(String tokenName) {
        return null;
    }

    @Override
    public List<PaperToken> getAllTokens(Predicate<PaperToken> predicate) {
        return null;
    }

    @Override
    public Predicate<? super PaperToken> wasPrintedInSets(List<String> allowedSetCodes) {
        return null;
    }

    @Override
    public Iterator<PaperToken> iterator() {
        synchronized (allTokenByName) {
            return new ArrayList<>(allTokenByName.values()).iterator();
        }
    }

    public Map<String, CardRules> getRules() { return this.rulesByName;}
}
