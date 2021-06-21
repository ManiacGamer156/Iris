package net.coderbot.iris.shaderpack.option;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.coderbot.iris.shaderpack.parsing.ParsedString;

import java.util.regex.Matcher;

/**
 * This class encapsulates the source code of a single shader source file along with the
 * corresponding configurable options within the source file.
 *
 * The shader configuration system revolves around a carefully defined way of directly editing
 * shader source files in order to change configuration options. This class handles the first
 * step of that process—discovering configurable options from shader source files—as well as
 * the final step of that process—editing shader source files to apply the modified values
 * of valid configuration options.
 *
 * Intermediate steps of that process include considering the annotated source for all shader
 * source files within a shader pack in order to deduplicate options that are common to multiple
 * source files, and discarding options that are ambiguous between source files. In addition,
 * another step includes loading changed option values from on-disk configuration files.
 *
 * The name "OptionAnnotatedSource" is based on the fact that this class simultaneously
 * stores a snapshot of the shader source code at the time of option discovery, as well
 * as data for each line ("annotations") about the relevant option represented by that
 * line, or alternatively an optional diagnostic message for that line saying why a potential
 * option was not parsed as a valid shader option.
 *
 * The data stored within this class is immutable. This ensures that once you have discovered
 * options from a given shader source file, that you may then apply any changed option values
 * without having to re-parse the shader source code for options, and without risking having
 * the shader source code fall out of sync with the annotations.
 */
public final class OptionAnnotatedSource {
	/**
	 * The content of each line within this shader source file.
	 */
	private final ImmutableList<String> lines;

	private final ImmutableMap<Integer, BooleanOption> booleanOptions;
	private final ImmutableMap<Integer, StringOption> stringOptions;

	/**
	 * Optional diagnostic messages for each line. The parser may notice that though a shader pack 
	 * author may have intended for a line to be a valid option, Iris might have ignored it due to
	 * a syntax error or some other issue.
	 *
	 * These diagnostic messages provide reasons for why Iris decided to ignore a plausible option
	 * line, as well as hints for how an invalid option line can be modified to be a valid one.
	 */
	private final ImmutableMap<Integer, String> diagnostics;

	/**
	 * Keeps track of references to boolean #define options. Corelletes the name of the #define
	 * option to one of the lines it was referenced on.
	 *
	 * References to boolean #define options that happen in plain #if directives are not analyzed
	 * for the purposes of determining whether a boolean #define option is referenced or not, to 
	 * match OptiFine behavior. Though this might have originally been an oversight, shader packs
	 * now anticipate this behavior, so it must be replicated here. Since it would be complex to 
	 * fully parse #if directives, this also makes the code simpler.
	 */
	private final ImmutableMap<String, Integer> booleanDefineReferences;

	/**
	 * Parses the lines of a shader source file in order to locate valid options from it.
	 */
	public OptionAnnotatedSource(ImmutableList<String> lines) {
		this.lines = lines;

		AnnotationsBuilder builder = new AnnotationsBuilder();

		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			parseLine(builder, index, line);
		}

		this.booleanOptions = builder.booleanOptions.build();
		this.stringOptions = builder.stringOptions.build();
		this.diagnostics = builder.diagnostics.build();
		this.booleanDefineReferences = builder.booleanDefineReferences.build();
	}

	private static void parseLine(AnnotationsBuilder builder, int index, String lineText) {
		// Check to see if this line contains anything of interest before we try to parse it.
		if (!lineText.contains("#define")
			&& !lineText.contains("const")
			&& !lineText.contains("#ifdef")
			&& !lineText.contains("#ifndef")) {
			// Nothing of interest.
			return;
		}

		// Parse the trimmed form of the line to ignore indentation and trailing whitespace.
		ParsedString line = new ParsedString(lineText.trim());

		if (line.takeLiteral("#ifdef") || line.takeLiteral("#ifndef")) {
			// The presence of #ifdef and #ifndef directives is used to determine whether a given
			// boolean option should be recognized as a configurable option.
			//
			// As noted above, #if and #elif directives are not checked even though they may also
			// contain references.
			parseIfdef(builder, index, line);
		} else if (line.takeLiteral("const")) {
			if (!line.takeSomeWhitespace()) {
				return;
			}

			// TODO: Parse const option
			builder.diagnostics.put(index, "Const options aren't currently supported.");
		} else if (line.currentlyContains("#define")) {
			parseDefineOption(builder, index, line);
		}
	}

	private static void parseIfdef(AnnotationsBuilder builder, int index, ParsedString line) {
		if (!line.takeSomeWhitespace()) {
			return;
		}

		String name = line.takeWord();

		line.takeSomeWhitespace();

		if (name == null || !line.isEnd()) {
			return;
		}

		builder.booleanDefineReferences.put(name, index);
	}

	private static void parseDefineOption(AnnotationsBuilder builder, int index, ParsedString line) {
		// Remove the leading comment for processing.
		boolean hasLeadingComment = line.takeComments();

		if (!line.takeLiteral("#define")) {
			builder.diagnostics.put(index,
					"This line contains an occurrence of \"#define\" " +
					"but it wasn't in a place we expected, ignoring it.");
			return;
		}

		if (!line.takeSomeWhitespace()) {
			builder.diagnostics.put(index,
					"This line properly starts with a #define statement but doesn't have " +
					"any whitespace characters after the #define.");
			return;
		}

		String name = line.takeWord();

		if (name == null) {
			builder.diagnostics.put(index,
					"Invalid syntax after #define directive. " +
					"No alphanumeric or underscore characters detected.");
			return;
		}

		// Maybe take some whitespace
		boolean tookWhitespace = line.takeSomeWhitespace();

		if (line.isEnd()) {
			// Plain define directive without a comment.
			builder.booleanOptions.put(index, new BooleanOption(OptionType.DEFINE, name, null, !hasLeadingComment));
			return;
		}

		if (line.takeComments()) {
			// Note that this is a bare comment, we don't need to look for the allowed values part.
			// Obviously that part isn't necessary since boolean options only have two possible 
			// values (true and false)
			String comment = line.takeRest().trim();

			builder.booleanOptions.put(index, new BooleanOption(OptionType.DEFINE, name, comment, !hasLeadingComment));
			return;
		} else if (!tookWhitespace) {
			// Invalid syntax.
			builder.diagnostics.put(index,
				"Invalid syntax after #define directive. Only alphanumeric or underscore " +
				"characters are allowed in option names.");

			return;
		}

		if (hasLeadingComment) {
			builder.diagnostics.put(index,
				"Ignoring potential non-boolean #define option since it has a leading comment. " +
				"Leading comments (//) are only allowed on boolean #define options.");
			return;
		}

		String value = line.takeNumber();

		if (value == null) {
			value = line.takeWord();
		}

		if (value == null) {
			builder.diagnostics.put(index, "Ignoring this #define directive because it doesn't appear to be a boolean #define, " +
				"and its potential value wasn't a valid number or a valid word.");
			return;
		}

		tookWhitespace = line.takeSomeWhitespace();

		if (line.isEnd()) {
			builder.stringOptions.put(index, StringOption.createUncommented(OptionType.DEFINE, name, value));
			return;
		} else if (!tookWhitespace) {
			builder.diagnostics.put(index,
				"Invalid syntax after value #define directive. " +
				"Invalid characters after number or word.");
			return;
		}

		if (!line.takeComments()) {
			builder.diagnostics.put(index,
				"Invalid syntax after value #define directive. " +
				"Only comments may come after the value.");
			return;
		}

		String comment = line.takeRest().trim();

		builder.stringOptions.put(index, StringOption.create(OptionType.DEFINE, name, comment, value));


		/*
	    //#define   SHADOWS // Whether shadows are enabled
		SHADOWS // Whether shadows are enabled
		// Whether shadows are enabled
		Whether shadows are enabled
			


		#define OPTION 0.5 // A test option
		OPTION 0.5 // A test option
		0.5 // A test option
		*/
	}

	public ImmutableMap<Integer, BooleanOption> getBooleanOptions() {
		return booleanOptions;
	}

	public ImmutableMap<Integer, StringOption> getStringOptions() {
		return stringOptions;
	}

	public ImmutableMap<Integer, String> getDiagnostics() {
		return diagnostics;
	}

	public ImmutableMap<String, Integer> getBooleanDefineReferences() {
		return booleanDefineReferences;
	}

	public String apply(OptionValues values) {
		StringBuilder source = new StringBuilder();

		for (int index = 0; index < lines.size(); index++) {
			source.append(edit(values, index, lines.get(index)));
			source.append('\n');
		}

		return source.toString();
	}

	private String edit(OptionValues values, int index, String existing) {
		// See if it's a boolean option
		BooleanOption booleanOption = booleanOptions.get(index);

		if (booleanOption != null) {
			if (values.shouldFlip(booleanOption.getName())) {
				return flipBooleanDefine(existing);
			} else {
				return existing;
			}
		}

		StringOption stringOption = stringOptions.get(index);

		if (stringOption != null) {
			// TODO
			throw new UnsupportedOperationException("not yet implemented");
		}

		// TODO: Other option types?

		return existing;
	}

	private static boolean hasLeadingComment(String line) {
		return line.trim().startsWith("//");
	}

	private static String removeLeadingComment(String line) {
		// TODO: What about ///#define OPTION
		return line.replaceFirst(Matcher.quoteReplacement("//"), "");
	}

	private static String flipBooleanDefine(String line) {
		if (hasLeadingComment(line)) {
			return removeLeadingComment(line);
		} else {
			return "//" + line;
		}
	}

	private static class AnnotationsBuilder {
		private final ImmutableMap.Builder<Integer, BooleanOption> booleanOptions;
		private final ImmutableMap.Builder<Integer, StringOption> stringOptions;
		private final ImmutableMap.Builder<Integer, String> diagnostics;
		private final ImmutableMap.Builder<String, Integer> booleanDefineReferences;

		private AnnotationsBuilder() {
			booleanOptions = ImmutableMap.builder();
			stringOptions = ImmutableMap.builder();
			diagnostics = ImmutableMap.builder();
			booleanDefineReferences = ImmutableMap.builder();
		}
	}
}
