package jobhunter;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Spots jobs a school leaver can start without experience, and jobs that train you on the job.
 * A job that asks for years of experience or a degree is never labelled "no experience" or "matric is enough".
 * Pure function over the job's title and description; it only labels, it never hides or accepts a job by itself.
 */
final class Entry {
    private Entry() {}

    record Result(boolean matric, boolean noExperience, boolean training) {
        boolean any() { return matric || noExperience || training; }
        List<String> labels() {
            List<String> out = new ArrayList<>();
            if (matric) out.add("Matric is enough");
            if (noExperience) out.add("No experience needed");
            if (training) out.add("Training provided");
            return out;
        }
    }

    private static final Pattern MATRIC = Pattern.compile(
            "\\bmatric(ulation)?\\b|\\bgrade 12\\b|national senior certificate|\\bnsc\\b|school[- ]leavers?");
    private static final Pattern NO_EXP = Pattern.compile(
            "\\bno (prior |previous |relevant |formal |work )*experience( is)?( (needed|required|necessary))?|"
          + "\\bexperience (is )?not (needed|required|necessary)|\\bentry[- ]level\\b|\\bfresh graduates?\\b|\\bwithout experience\\b");
    private static final Pattern TRAINING = Pattern.compile(
            "\\bwe(?: will|'ll| ll)? train\\b|\\bwill be trained\\b|\\b(full|paid|free|comprehensive|hands-on|on-the-job|on the job) training\\b|"
          + "\\btraining (is )?(provided|given|offered|included)\\b|\\blearnerships?\\b|\\bapprentice(ship)?s?\\b|\\btrainee(ship)?s?\\b|"
          + "\\bmentorship\\b.{0,40}\\btraining\\b|\\btraining\\b.{0,40}\\bmentorship\\b|\\bnot required.{0,40}\\bwill train\\b");
    /** "2+ years of experience", "3-5 years experience": any real experience requirement. */
    private static final Pattern YEARS_REQUIRED = Pattern.compile(
            "\\b\\d{1,2}\\s*\\+?\\s*(?:(?:-|to)\\s*\\d{1,2}\\s*)?years?'?\\s+(?:of\\s+)?(?:\\w+\\s+){0,3}experience\\b|"
          + "\\bminimum (of )?\\d{1,2}\\s*years?\\b");
    private static final Pattern DEGREE_REQUIRED = Pattern.compile(
            "\\b(degree|bachelor'?s?|b\\.?sc|honours|diploma|nqf ?(level)? ?[5-9])\\b.{0,25}\\b(required|essential|minimum|compulsory|needed)\\b|"
          + "\\b(requires?|required|minimum|must have|essential)\\b.{0,25}\\b(degree|bachelor'?s?|b\\.?sc|honours|diploma|nqf ?(level)? ?[5-9])\\b");
    private static final Pattern SENIOR_TITLE = Pattern.compile("\\b(senior|sr|lead|principal|head|manager|director|staff)\\b");

    static Result detect(Job j) {
        String title = Text.norm(j.title);
        String text = Text.norm(j.title + " " + j.description).replace('’', '\'');
        if (SENIOR_TITLE.matcher(title).find()) return new Result(false, false, false);
        boolean years = YEARS_REQUIRED.matcher(text).find();
        boolean degree = DEGREE_REQUIRED.matcher(text).find();
        boolean entryOk = !years && !degree;
        return new Result(
                entryOk && MATRIC.matcher(text).find(),
                entryOk && NO_EXP.matcher(text).find(),
                !years && TRAINING.matcher(text).find());
    }

    static List<String> labels(Job j) { return detect(j).labels(); }
}
