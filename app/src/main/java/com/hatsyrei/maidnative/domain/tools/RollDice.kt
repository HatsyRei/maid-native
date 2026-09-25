package com.hatsyrei.maidnative.domain.tools

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Fair dice, so a tabletop session's rolls come from chance rather than the model's imagination. */
object RollDice : Tool {
    override val name = "roll_dice"
    override val label = "Dice roller"
    override val summary = "Roll dice, like 2d6 or 1d20+5."
    override val description =
        "Roll dice with a fair random number generator. Always use this for dice rolls instead of " +
            "inventing a result. Example: a D&D check of 1d20+5 is count 1, sides 20, modifier 5."

    private const val MAX_COUNT = 100
    private const val MAX_SIDES = 1000
    private const val MAX_MODIFIER = 1000

    override fun parameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("count", integer("Number of dice to roll. Defaults to 1.", 1, MAX_COUNT))
                .put("sides", integer("Sides on each die, e.g. 20 for a d20.", 2, MAX_SIDES))
                .put("modifier", integer("Added to the total, e.g. -1 for 1d6-1. Defaults to 0.", -MAX_MODIFIER, MAX_MODIFIER)),
        )
        .put("required", JSONArray().put("sides"))

    override suspend fun invoke(arguments: JSONObject): String = roll(arguments, Random.Default)

    internal fun roll(arguments: JSONObject, random: Random): String {
        val count = arguments.int("count", default = 1, range = 1..MAX_COUNT)
        val sides = arguments.int("sides", default = null, range = 2..MAX_SIDES)
        val modifier = arguments.int("modifier", default = 0, range = -MAX_MODIFIER..MAX_MODIFIER)
        val rolls = List(count) { random.nextInt(1, sides + 1) }
        val notation = "${count}d$sides" + when {
            modifier > 0 -> "+$modifier"
            modifier < 0 -> "$modifier"
            else -> ""
        }
        return JSONObject()
            .put("notation", notation)
            .put("rolls", JSONArray(rolls))
            .put("modifier", modifier)
            .put("total", rolls.sum() + modifier)
            .toString()
    }

    private fun integer(description: String, min: Int, max: Int): JSONObject = JSONObject()
        .put("type", "integer")
        .put("description", description)
        .put("minimum", min)
        .put("maximum", max)
}
