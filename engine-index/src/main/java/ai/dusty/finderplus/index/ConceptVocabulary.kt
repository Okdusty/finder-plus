package ai.rightone.finderplus.index

/**
 * The concept space the app recognizes without being taught — organized as a **hierarchy**, which is
 * what lets it reach general-purpose breadth on a phone.
 *
 * ### The vocabulary is data, not code
 *
 * The concepts themselves are **not** compiled in. They are loaded from an editable resource
 * (`assets/vocab/concepts.txt`, overridable at runtime by `files/vocab/concepts.txt`) and [parse]d
 * into this structure, so the recognized vocabulary can be changed, regionalised or replaced without
 * touching the engine. This class is only the shape and the derived indexes over that data.
 *
 * ### Why a hierarchy and not one long list
 *
 * Zero-shot labels come from a softmax, so probability mass is conserved: every concept added takes
 * mass from every other. A flat 5,000-entry vocabulary is therefore *worse* than a 300-entry one — the
 * correct answer competes with thousands of irrelevant concepts and lands under any useful confidence
 * floor. That is the reason a naive "just add more labels" approach degrades instead of improving.
 *
 * So recognition runs in two stages, the way a person narrows things down:
 *
 *  1. **Gate** — score the coarse [gates] ("is this a person? a place? a document?"). Cheap, and the
 *     domains are visually distinct enough to be reliable.
 *  2. **Expand** — score only the winning domains' fine concepts. Each softmax stays small and sharp,
 *     yet the reachable vocabulary is the union of all domains.
 *
 * ### Named entities
 *
 * CLIP is trained on web image-text pairs, so it genuinely carries knowledge of famous people,
 * landmarks, brands and characters. A domain flagged [Domain.entity] exposes that, gated behind its own
 * branch so it never competes with ordinary concepts. Identity recognition from a 224 px whole-frame
 * embedding is materially less reliable than the rest of this vocabulary, and confusing one person for
 * another is a worse error than missing them — so entity domains are held to [ENTITY_MIN_PROB] rather
 * than the ordinary floor, and their guesses are *suggestions the user confirms*, never auto-applied.
 */
class ConceptVocabulary(val domains: List<Domain>) {

    /**
     * One branch of the hierarchy.
     *
     * @param name short domain id, also written as the item's coarse category.
     * @param gate caption-style phrase used to decide whether this branch applies at all.
     * @param concepts fine-grained labels scored only when the gate wins.
     * @param entity named-entity domain: higher confidence floor, proposal-only (see class doc).
     */
    data class Domain(
        val name: String,
        val gate: String,
        val concepts: List<String>,
        val entity: Boolean = false,
    )

    /** Gate phrases, in domain order — the stage-1 vocabulary. */
    val gates: List<String> = domains.map { it.gate }

    /** Every concept and every gate, de-duplicated and normalized. All of these need a stored text prior. */
    val all: List<String> = (gates + domains.flatMap { it.concepts })
        .map { it.trim().lowercase() }
        .distinct()

    /** Concepts belonging to any entity domain, lowercased for prototype lookup. */
    val entityConcepts: Set<String> =
        domains.filter { it.entity }.flatMap { it.concepts }.map { it.lowercase() }.toSet()

    /** Domain whose gate matches, or null. */
    fun domainOf(gate: String): Domain? =
        domains.firstOrNull { it.gate.equals(gate, ignoreCase = true) }

    /** True if [label] (any case) is a named-entity concept — the proposal-only, higher-floor class. */
    fun isEntity(label: String): Boolean = label.lowercase() in entityConcepts

    val size: Int get() = all.size

    companion object {
        /**
         * Identity and brand guesses must clear a higher bar than ordinary concepts: the vocabulary is
         * large, the visual evidence is a whole-frame thumbnail, and a confidently wrong name is the one
         * output of this system a user would consider broken rather than imperfect.
         */
        const val ENTITY_MIN_PROB = 0.35f

        /** How many gates to expand. More than one because real photos belong to several domains. */
        const val GATES_TO_EXPAND = 3

        /** Separator between a domain's fields on a header line: `name :: gate [:: entity]`. */
        private const val SEP = " :: "
        private const val ENTITY_MARK = "entity"

        /**
         * Parse the on-disk vocabulary format (see `assets/vocab/concepts.txt`):
         *  - blank lines and `#` comments are ignored;
         *  - a line containing `::` opens a domain — `name :: gate` or `name :: gate :: entity`;
         *  - every other line is a concept of the current domain.
         */
        fun parse(text: String): ConceptVocabulary {
            val domains = ArrayList<Domain>()
            var name: String? = null
            var gate = ""
            var entity = false
            val concepts = ArrayList<String>()

            fun flush() {
                val n = name ?: return
                domains += Domain(n, gate, concepts.toList(), entity)
                concepts.clear()
            }

            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.contains(SEP)) {
                    flush()
                    val parts = line.split(SEP)
                    name = parts[0].trim()
                    gate = parts.getOrNull(1)?.trim().orEmpty()
                    entity = parts.getOrNull(2)?.trim().equals(ENTITY_MARK, ignoreCase = true)
                } else {
                    concepts += line
                }
            }
            flush()
            return ConceptVocabulary(domains)
        }
    }
}
