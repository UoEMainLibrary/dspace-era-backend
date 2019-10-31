package uk.ac.edina.datashare.authenticate;

import java.sql.SQLException;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.dspace.authenticate.AuthenticationMethod;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.UUN2EmailService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;

import uk.ac.edina.datashare.eperson.DSpaceAccount;
import uk.ac.edina.datashare.utils.DSpaceUtils;

/**
 * EASE / cosign authentication class. This uses a cosign filter for
 * authenticating users.
 */
public class EASEAuthentication implements AuthenticationMethod {
	private static Logger LOG = Logger.getLogger(EASEAuthentication.class);

	/** University user name string */
	public static final String UUN = "uun";

	/** DSpace context string */
	public static final String CONTEXT = "dspace_context";

	/** DSpace EPerson string */
	public static final String EPERSON = "eperson";

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.dspace.authenticate.AuthenticationMethod#allowSetPassword(org.dspace.core
	 * .Context, javax.servlet.http.HttpServletRequest, java.lang.String)
	 */
	public boolean allowSetPassword(Context context, HttpServletRequest arg1, String arg2) throws SQLException {
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.dspace.authenticate.AuthenticationMethod#authenticate(org.dspace.core.
	 * Context, java.lang.String, java.lang.String, java.lang.String,
	 * javax.servlet.http.HttpServletRequest)
	 */
	public int authenticate(Context context, String arg1, String arg2, String arg3, HttpServletRequest request)
			throws SQLException {
		return -1;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.dspace.authenticate.AuthenticationMethod#canSelfRegister(org.dspace.core.
	 * Context, javax.servlet.http.HttpServletRequest, java.lang.String)
	 */
	public boolean canSelfRegister(Context arg0, HttpServletRequest arg1, String arg2) throws SQLException {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.dspace.authenticate.AuthenticationMethod#getSpecialGroups(org.dspace.core
	 * .Context, javax.servlet.http.HttpServletRequest)
	 */
	public List<Group> getSpecialGroups(Context context, HttpServletRequest request) throws SQLException {
		return DSpaceUtils.getSpecialGroups(context, request);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.dspace.authenticate.AuthenticationMethod#initEPerson(org.dspace.core.
	 * Context, javax.servlet.http.HttpServletRequest, org.dspace.eperson.EPerson)
	 */
	public void initEPerson(Context context, HttpServletRequest request, EPerson eperson) throws SQLException {
		String email = eperson.getEmail();
//		DatasetService datasetService = ContentServiceFactory.getInstance().getDatasetService();
		UUN2EmailService uun2EmailService = ContentServiceFactory.getInstance().getUUN2EmailService();
		String uun = uun2EmailService.fetchUUN(context, email);

		if (uun != null) {
			DSpaceAccount.updateNetId(context, eperson, uun);
		} else {
			LOG.warn("Account created with no uun for " + eperson.getEmail());
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.dspace.authenticate.AuthenticationMethod#isImplicit()
	 */
	public boolean isImplicit() {
		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.dspace.authenticate.AuthenticationMethod#loginPageTitle(org.dspace.core.
	 * Context)
	 */
	public String loginPageTitle(Context arg0) {
		return "EASE authentication";
	}

	/**
	 * Use the request for login URL to authenticate user.
	 * 
	 * @param context  DSpace context.
	 * @param request
	 * @param response
	 */
	public String loginPageURL(Context context, HttpServletRequest request, HttpServletResponse response) {
		return "ease-login";
	}
}
