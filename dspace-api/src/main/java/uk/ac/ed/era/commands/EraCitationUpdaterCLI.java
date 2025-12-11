package uk.ac.ed.era.commands;

import java.sql.SQLException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.StringTokenizer;
import java.util.stream.StreamSupport;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Context;

/**
 * Functionality to create or update citations for items using
 * the Dspace CLI. When run it creates citations for items with missing
 * citations
 * stored in the metdata field dc.identifier.citation. Further, if intially the
 * DOI was missing
 * and subsequently registered. Then the citation is updated with the doi.
 * Once the doi is added to citation it won't be updated by running this class.
 * 
 * This is configured in dspace/config/launcher.xml.
 * Then we run the script in a Cron job periodically., e.g,
 * 15 8-19 * * * $DSPACE/bin/dspace citation-updater -c >
 * $DSPACE/log/era-citation-updater.log 2>&1
 * (Note the call requires flag -c.)
 * 
 * @author John Pinto
 * 
 * 
 */
public class EraCitationUpdaterCLI {

	private static final Logger log = LogManager.getLogger(EraCitationUpdaterCLI.class);

	private Context context;

	public EraCitationUpdaterCLI(Context context) {
		this.context = context;
	}

	/**
	 * 
	 * @param argv
	 */
	public static void main(String[] argv) {
		// create an options object and populate it
		CommandLineParser parser = new PosixParser();

		Options options = new Options();

		options.addOption("c", "create citations", false, "Create or update citation for items.");

		EraCitationUpdaterCLI du = new EraCitationUpdaterCLI(new Context());
		HelpFormatter helpformater = new HelpFormatter();
		try {
			CommandLine line = parser.parse(options, argv);
			if (line.hasOption('c')) {
				log.info("Started Creating citations");
				System.out.println("Started Creating citations");
				du.createCitations();
				log.info("Completed Creating citations");
				System.out.println("Completed Creating citations");
			} else {
				helpformater.printHelp("\nEra DOI\n", options);
			}
		} catch (ParseException ex) {
			log.info(ex);
			System.out.println(ex.getMessage());
			helpformater.printHelp("\nEra DOI\n", options);
		}
	}

	/**
	 * Create a citation for all items that have a new doi.
	 */
	private void createCitations() {
		context.turnOffAuthorisationSystem();

		try {
			ItemService itemService = ContentServiceFactory.getInstance().getItemService();
			// Convert iterator to stream and process items functionally
			StreamSupport.stream(
					Spliterators.spliteratorUnknownSize(
							itemService.findAll(context),
							Spliterator.ORDERED),
					false)
					.filter(item -> needsCitationUpdate(item))
					.forEach(item -> processItemCitation(item));

			context.complete();
		} catch (SQLException ex) {
			throw new RuntimeException(ex);
		} finally {
			context.restoreAuthSystemState();
		}

	}

	/**
	 * Checks if item needs a citation or needs updating.
	 * 
	 * @param item
	 * @return boolean
	 */
	private boolean needsCitationUpdate(Item item) {
		ItemService itemService = ContentServiceFactory.getInstance().getItemService();

		// Get citation directly using DSpace API
		List<MetadataValue> citations = itemService.getMetadata(item, "dc", "identifier", "citation", Item.ANY, false);
		String citation = citations.isEmpty() ? null : citations.get(0).getValue();

		// Check if item has DOI directly using DSpace API
		List<MetadataValue> identifiers = itemService.getMetadata(item, "dc", "identifier", "uri", Item.ANY, false);
		boolean hasDoi = identifiers.stream()
				.anyMatch(identifier -> identifier.getValue().contains("doi.org"));

		log.info("Item {} citation: '{}' hasDoi: {}", item.getID(), citation, hasDoi);

		// Case 1: Has no citation
		boolean needsNewCitation = citation == null;

		// Case 2: Has doi, but citation doesn't contain the DOI URL
		boolean needsUpdatedCitation = hasDoi && citation != null && !citation.contains("doi.org");

		log.info("Item {} needsNewCitation: {} needsUpdatedCitation: {}",
				item.getID(), needsNewCitation, needsUpdatedCitation);

		return needsNewCitation || needsUpdatedCitation;
	}

	/**
	 * Process that creates or updates item's citation.
	 * 
	 * @param item
	 */
	private void processItemCitation(Item item) {
		try {
			ItemService itemService = ContentServiceFactory.getInstance().getItemService();

			// Get current citation
			List<MetadataValue> citations = itemService.getMetadata(item, "dc", "identifier", "citation", Item.ANY,
					false);
			String citation = citations.isEmpty() ? null : citations.get(0).getValue();

			// Check if item has DOI
			List<MetadataValue> identifiers = itemService.getMetadata(item, "dc", "identifier", "uri", Item.ANY, false);
			boolean hasDoi = identifiers.stream()
					.anyMatch(identifier -> identifier.getValue().contains("doi.org"));

		    // Check that citation is using dc.title alternative for title if it exists.
			boolean useAltTitle = false;
			List<MetadataValue> titles = itemService.getMetadata(item, "dc", "title", Item.ANY, Item.ANY, false);
			String title = titles.isEmpty() ? null : titles.get(0).getValue();
			List<MetadataValue> altTitles = itemService.getMetadata(item, "dc", "title", "alternative", Item.ANY, false);
            String altTitle = altTitles.isEmpty() ? null : altTitles.get(0).getValue();
			if (altTitle != null && title != null && !altTitle.equals(title)) {
				useAltTitle = true;
			}

			log.info("Item " + item.getID() + " citation: " + citation);

			if (citation == null) {
				// Create new citation
				String newCitation = createCitation(item);
				if (newCitation != null) {
					itemService.addMetadata(context, item, "dc", "identifier", "citation", "en", newCitation);
				}
				// If citation exists:
				// If citation doesn't contain doi.org, but item has doi: update citation.
				// If citation is using dc.title for title, when dc.title.alternative exists: update citation.
			} else if (citation != null && ((!citation.contains("doi.org") && hasDoi) || useAltTitle)) {
				// Clear existing citation and create new one
				itemService.clearMetadata(context, item, "dc", "identifier", "citation", Item.ANY);
				String newCitation = createCitation(item);
				if (newCitation != null) {
					itemService.addMetadata(context, item, "dc", "identifier", "citation", "en", newCitation);
					log.info("Item " + item.getID() + " has new citation: " + newCitation);
				}
			}

			itemService.update(context, item);
		} catch (AuthorizeException | SQLException ex) {
			log.info("Error updating citation for item " + item.getID() + ": " + ex.getMessage());
		}
	}

	/**
	 * Create a citation for a given DSpace item using DSpace core APIs
	 */
	private String createCitation(Item item) {
		try {
			ItemService itemService = ContentServiceFactory.getInstance().getItemService();
			StringBuilder buffer = new StringBuilder(200);

			// Get authors
			List<MetadataValue> authors = itemService.getMetadata(item, "dc", "contributor", "author", Item.ANY, false);
			boolean authorGiven = !authors.isEmpty();

			if (authorGiven) {
				// Add authors
				for (int i = 0; i < authors.size(); i++) {
					if (i > 0) {
						buffer.append("; ");
					}
					buffer.append(authors.get(i).getValue());
				}
				buffer.append(". ");
			} else {
				// Add publisher if no authors
				List<MetadataValue> publishers = itemService.getMetadata(item, "dc", "publisher", Item.ANY, Item.ANY,
						false);
				if (!publishers.isEmpty()) {
					buffer.append(" ");
					buffer.append(publishers.get(0).getValue());
					buffer.append(".");
				}
				buffer.append(" ");
			}

			// Add date available year if available
			buffer.append("(");
			List<MetadataValue> dateAvailable = itemService.getMetadata(item, "dc", "date", "available", Item.ANY,
					false);
			if (!dateAvailable.isEmpty()) {
				String dateStr = dateAvailable.get(0).getValue();
				// Extract year from date string (assuming format like "2023-01-01" or "2023")
				String year = dateStr.length() >= 4 ? dateStr.substring(0, 4) : dateStr;
				buffer.append(year);
			} else {
				// No date available, use current year
				Calendar calendar = new GregorianCalendar();
				calendar.setTime(new Date());
				buffer.append(calendar.get(Calendar.YEAR));
			}
			buffer.append("). ");

			// Add title
			// See if dc.title.alternative is available
			List<MetadataValue> titles = itemService.getMetadata(item, "dc", "title", "alternative", Item.ANY, false);
			if (!titles.isEmpty()) {
				buffer.append(titles.get(0).getValue());
			} else {
				// Otherwise use dc.title if available
				titles = itemService.getMetadata(item, "dc", "title", Item.ANY, Item.ANY, false);
				if (!titles.isEmpty()) {
					buffer.append(titles.get(0).getValue());
				}
			}
			buffer.append(", ");

			// Add time period if available
			List<MetadataValue> temporal = itemService.getMetadata(item, "dc", "coverage", "temporal", Item.ANY, false);
			if (!temporal.isEmpty()) {
				String timePeriod = temporal.get(0).getValue();
				String[] dates = decodeTimePeriod(timePeriod);

				if (dates != null && dates.length == 2) {
					String from = dates[0].length() >= 4 ? dates[0].substring(0, 4) : dates[0];
					String to = dates[1].length() >= 4 ? dates[1].substring(0, 4) : dates[1];

					if (from.equals(to)) {
						timePeriod = from;
					} else {
						timePeriod = from + "-" + to;
					}

					buffer.append(timePeriod);
					buffer.append(" ");
				}
			}

			// Add item type
			List<MetadataValue> types = itemService.getMetadata(item, "dc", "type", Item.ANY, Item.ANY, false);
			buffer.append("[");
			if (!types.isEmpty()) {
				buffer.append(types.get(0).getValue());
			}
			buffer.append("].");

			// Append publisher if author is specified
			if (authorGiven) {
				List<MetadataValue> publishers = itemService.getMetadata(item, "dc", "publisher", Item.ANY, Item.ANY,
						false);
				if (!publishers.isEmpty()) {
					buffer.append(" ");
					buffer.append(publishers.get(0).getValue());
					buffer.append(".");
				}
			}

			// Add DOI if available
			List<MetadataValue> identifiers = itemService.getMetadata(item, "dc", "identifier", "uri", Item.ANY, false);
			for (MetadataValue identifier : identifiers) {
				if (identifier.getValue().contains("doi.org")) {
					buffer.append(" ");
					buffer.append(identifier.getValue());
					buffer.append(".");
					break;
				}
			}

			return buffer.toString();

		} catch (Exception e) {
			log.info("Error creating citation for item " + item.getID() + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * Decode time period W3CDTF profile of ISO 8601.
	 * (Copied from EraDspaceUtils since we can't use it)
	 */
	private String[] decodeTimePeriod(String encoding) {
		String[] dates = null;

		if (encoding != null) {
			String startStr = null;
			String endStr = null;

			// get tokens delimited by ";"- there should be three -
			// start=, end= and scheme=
			StringTokenizer st = new StringTokenizer(encoding, ";");

			if (st.countTokens() > 1) {
				for (int i = 0; i < st.countTokens(); i++) {
					if (i == 0) {
						startStr = st.nextToken();
					} else if (i == 1) {
						endStr = st.nextToken();
					} else {
						break;
					}
				}

				String startArray[] = startStr.split("=");
				String endArray[] = endStr.split("=");

				if (startArray.length == 2 || endArray.length == 2) {
					dates = new String[2];
				}

				if (startArray.length == 2) {
					dates[0] = startArray[1];
				}

				if (endArray.length == 2) {
					dates[1] = endArray[1];
				}
			}
		}

		return dates;
	}

}