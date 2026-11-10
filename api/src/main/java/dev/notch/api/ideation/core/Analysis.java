package dev.notch.api.ideation.core;

import java.util.List;

/**
 * What a comparison currently says.
 *
 * @param standings     options from best to worst; {@code percent} is the weighted score as a
 *                      share of the best possible
 * @param winnerIds     the leading option(s); more than one means a tie
 * @param robustness    share of randomly reweighted runs each option wins, best first
 * @param decisive      criteria that create the leader's lead over the runner-up, largest first
 * @param unscored      cells still empty
 * @param problems      reasons the comparison can't be trusted yet, in plain words
 */
public record Analysis(List<Standing> standings, List<String> winnerIds, List<Share> robustness, int samples,
                       List<Driver> decisive, int unscored, List<String> problems) {

    public record Standing(String optionId, String name, double percent, int rank) {
    }

    public record Share(String optionId, String name, double share) {
    }

    /** {@code points}: how much of the lead this criterion gives, in weighted score points. */
    public record Driver(String criterionId, String name, double points) {
    }

    public boolean ready() {
        return problems.isEmpty() && unscored == 0 && winnerIds.size() == 1;
    }
}
