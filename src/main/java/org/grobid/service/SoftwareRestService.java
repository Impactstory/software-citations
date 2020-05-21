package org.grobid.service;

import com.sun.jersey.multipart.FormDataParam;
import com.sun.jersey.spi.resource.Singleton;

import org.grobid.core.main.LibraryLoader;
import org.grobid.core.lexicon.SoftwareLexicon;
import org.grobid.core.utilities.GrobidProperties;
import org.grobid.core.utilities.SoftwareProperties;
import org.grobid.core.main.GrobidHomeFinder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.InputStream;

import java.util.Arrays;

/**
 * RESTful service for GROBID Software extension.
 *
 * @author Patrice
 */
@Singleton
@Path(SoftwarePaths.PATH_SOFTWARE)
public class SoftwareRestService implements SoftwarePaths {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoftwareRestService.class);

    private static final String TEXT = "text";
    private static final String DISAMBIGUATE = "disambiguate";
    private static final String XML = "xml";
    private static final String PDF = "pdf";
    private static final String INPUT = "input";

    public SoftwareRestService() {
        LOGGER.info("Init Servlet SoftwareRestService.");
        LOGGER.info("Init lexicon and KB resources.");
        try {
            String pGrobidHome = SoftwareProperties.get("grobid.home");

            GrobidHomeFinder grobidHomeFinder = new GrobidHomeFinder(Arrays.asList(pGrobidHome));
            GrobidProperties.getInstance(grobidHomeFinder);
    
            LOGGER.info(">>>>>>>> GROBID_HOME="+GrobidProperties.get_GROBID_HOME_PATH());

            if (SoftwareProperties.get("grobid.software.engine").toUpperCase().equals("WAPITI"))
                LibraryLoader.load();
            SoftwareLexicon.getInstance();
        } catch (final Exception exp) {
            System.err.println("GROBID software initialisation failed: " + exp);
            exp.printStackTrace();
        }

        LOGGER.info("Init of Servlet SoftwareRestService finished.");
    }

    /**
     * @see org.grobid.service.process.GrobidRestProcessGeneric#isAlive()
     */
    @Path(PATH_IS_ALIVE)
    @Produces(MediaType.TEXT_PLAIN)
    @GET
    public Response isAlive() {
        return Response.status(Response.Status.OK).entity(SoftwareProcessString.isAlive()).build();
    }

    @Path(PATH_SOFTWARE_TEXT)
    @Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
    @POST
    public Response processText_post(@FormParam(TEXT) String text, 
                                     @DefaultValue("0") @FormParam(DISAMBIGUATE) String disambiguate) {
        LOGGER.info(text); 
        boolean disambiguateBoolean = validateBooleanRawParam(disambiguate);

        return SoftwareProcessString.processText(text, disambiguateBoolean);
    }

    @Path(PATH_SOFTWARE_TEXT)
    @Produces(MediaType.APPLICATION_JSON + ";charset=utf-8")
    @GET
    public Response processText_get(@QueryParam(TEXT) String text, 
                                    @DefaultValue("0") @QueryParam(DISAMBIGUATE) String disambiguate) {
        //LOGGER.info(text);
        boolean disambiguateBoolean = validateBooleanRawParam(disambiguate);
        return SoftwareProcessString.processText(text, disambiguateBoolean);
    }
	
	@Path(PATH_ANNOTATE_SOFTWARE_PDF)
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	@Produces("application/json")
	@POST
	public Response processPDFAnnotation(@FormDataParam(INPUT) InputStream inputStream, 
                                         @DefaultValue("0") @FormDataParam(DISAMBIGUATE) String disambiguate) {
        boolean disambiguateBoolean = validateBooleanRawParam(disambiguate);
		return SoftwareProcessFile.processPDFAnnotation(inputStream, disambiguateBoolean);
	}

    private boolean validateBooleanRawParam(String raw) {
        boolean result = false;
        if ((raw != null) && (raw.equals("1") || raw.toLowerCase().equals("true"))) {
            result = true;
        }
        return result;
    }
}
