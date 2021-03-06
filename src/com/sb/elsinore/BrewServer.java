package com.sb.elsinore;

import jGPIO.InvalidGPIOException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Iterator;
import java.util.StringTokenizer;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.rendersnake.HtmlCanvas;

import com.sb.elsinore.NanoHTTPD.Response.Status;
import com.sb.elsinore.NanoHTTPD.Response;
import com.sb.elsinore.html.RenderHTML;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * A custom HTTP server for Elsinore. Designed to be very simple and lightweight.
 *
 * @author Doug Edey
 *
 */
public class BrewServer extends NanoHTTPD {

    /**
     * The Root Directory of the files to be served.
     */
    private File rootDir;

    /**
     * The Logger object.
     */
    public static final Logger LOG = Logger.getLogger("com.sb.manager.Server");

    /**
     * Hashtable mapping (String)FILENAME_EXTENSION -> (String)MIME_TYPE.
     */
    private static final Map<String, String> MIME_TYPES
        = new HashMap<String, String>() {
        /**
         * The Serial UID.
         */
        private static final long serialVersionUID = 1L;

        {
            put("css", "text/css");
            put("htm", "text/html");
            put("html", "text/html");
            put("xml", "text/xml");
            put("txt", "text/plain");
            put("asc", "text/plain");
            put("gif", "image/gif");
            put("jpg", "image/jpeg");
            put("jpeg", "image/jpeg");
            put("png", "image/png");
            put("mp3", "audio/mpeg");
            put("m3u", "audio/mpeg-url");
            put("mp4", "video/mp4");
            put("ogv", "video/ogg");
            put("flv", "video/x-flv");
            put("mov", "video/quicktime");
            put("swf", "application/x-shockwave-flash");
            put("js", "application/javascript");
            put("pdf", "application/pdf");
            put("doc", "application/msword");
            put("ogg", "application/x-ogg");
            put("zip", "application/octet-stream");
            put("exe", "application/octet-stream");
            put("class", "application/octet-stream");
            put("json", "application/json");
        }
    };

    /**
     * Convert a JSON paramaeter set into a hashmap.
     * @param params The incoming parameter listing
     * @return The converted parameters
     */
    public Map<String, String> ParseParams(Map<String, String> params) {
        Set<Entry<String, String>> incomingParams = params.entrySet();
        Map<String, String> parms;
        Iterator<Entry<String, String>> it = incomingParams.iterator();
        Entry<String, String> param = null;
        JSONObject incomingData = null;
        JSONParser parser = new JSONParser();
        String inputUnit = null;

        // Try to Parse JSON Data
        while (it.hasNext()) {
            param = it.next();
            BrewServer.LOG.info("Key: " + param.getKey());
            BrewServer.LOG.info("Entry: " + param.getValue());
            try {
                Object parsedData = parser.parse(param.getValue());
                if (parsedData instanceof JSONArray) {
                    incomingData = (JSONObject) ((JSONArray) parsedData).get(0);
                } else {
                    incomingData = (JSONObject) parsedData;
                    inputUnit = param.getKey();
                }
            } catch (Exception e) {
                BrewServer.LOG.info("couldn't read " + param.getValue()
                        + " as a JSON Value " + e.getMessage());
            }
        }

        if (incomingData != null) {
            // Use the JSON Data
            BrewServer.LOG.info("Found valid data for " + inputUnit);
            parms = (Map<String, String>) incomingData;
        } else {
            inputUnit = params.get("form");
            parms = params;
        }

        if (inputUnit != null) {
            parms.put("inputunit", inputUnit);
        }

        return parms;
    }

    /**
     * Constructor to create the HTTP Server.
     *
     * @param port
     *            The port to run on
     * @throws IOException
     *             If there's an issue starting up
     */
    public BrewServer(final int port) throws IOException {

        super(port);
        // default level, this can be changed
        initializeLogger(BrewServer.LOG);

        Level logLevel = Level.WARNING;
        String newLevel = System.getProperty("debug") != null ?
                                System.getProperty("debug"):
                                System.getenv("ELSINORE_DEBUG");
        if ("INFO".equalsIgnoreCase(newLevel)) {
            logLevel = Level.INFO;
        }

        BrewServer.LOG.info("Launching on port " + port);
        BrewServer.LOG.info("Enabled logging at level:" + logLevel.toString());
        BrewServer.LOG.setLevel(logLevel);

        this.rootDir = new File(BrewServer.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath()).getParentFile();

        if (System.getProperty("root_override") != null) {
            this.rootDir = new File(System.getProperty("root_override"));
            LOG.info("Overriding Root Directory from System Property: "
                    + rootDir.getAbsolutePath());
        }

        LOG.info("Root Directory is: " + rootDir.toString());

        if (rootDir.exists() && rootDir.isDirectory()) {
            LOG.info("Root directory: " + rootDir.toString());
        }
    }

    /**
     * Initialize the logger.  Look at the current logger and its parents to see
     * if it already has a handler setup.  If not, it adds one.
     *
     * @param logger
     *            The logger to initialize
     */
    private void initializeLogger(final Logger logger) {
        if (logger.getHandlers().length == 0) {
            if (logger.getParent() != null
                    && logger.getUseParentHandlers()) {
                initializeLogger(LOG.getParent());
            } else {
                Handler newHandler = new ConsoleHandler();
                logger.addHandler(newHandler);
            }
        }
    }

    /**
     * Parse the parameters to update the MashProfile.
     *
     * @param parms
     *            The parameters map from the original request
     * @return True if the profile is updated successfully.
     */
    private boolean updateMashProfile(final Map<String, String> parms) {
        String inputUnit = null;
        Set<Entry<String, String>> incomingParams = parms.entrySet();
        Iterator<Entry<String, String>> it = incomingParams.iterator();
        Entry<String, String> param = null;
        JSONObject incomingData = new JSONObject();
        JSONParser parser = new JSONParser();

        // Try to Parse JSON Data
        while (it.hasNext()) {
            param = it.next();
            BrewServer.LOG.info("Key: " + param.getKey());
            BrewServer.LOG.info("Entry: " + param.getValue());
            try {
                Object parsedData = parser.parse(param.getKey());
                if (parsedData instanceof JSONArray) {
                    incomingData = (JSONObject) ((JSONArray) parsedData).get(0);
                } else {
                    incomingData = (JSONObject) parsedData;
                }
            } catch (Exception e) {
                BrewServer.LOG.info("couldn't read " + param.getValue()
                        + " as a JSON Value " + e.getMessage());
            }
        }

        if (incomingData != null) {
            // Use the JSON Data
            BrewServer.LOG.info("Found valid data for " + inputUnit);
            BrewServer.LOG.info(incomingData.toJSONString());

            MashControl mControl = null;

            // Default to Fahrenheit
            String tempUnit = "F";
            if (incomingData.containsKey("temp_unit")) {
                tempUnit = incomingData.get("temp_unit").toString();
                incomingData.remove("temp_unit");
            }

            String pid = null;

            if (incomingData.containsKey("pid")) {
                pid = incomingData.get("pid").toString();
                mControl = LaunchControl.findMashControl(pid);

                if (mControl == null) {
                    // Add a new MashControl to the list
                    mControl = new MashControl();
                    LaunchControl.addMashControl(mControl);
                    mControl.setOutputControl(pid);
                }

                incomingData.remove("pid");
            } else {
                BrewServer.LOG.warning("Couldn't find the PID for this update");
                return false;
            }

            // Iterate through the JSON
            for (Object key : incomingData.keySet()) {
                String cKey = key.toString();

                try {

                    JSONObject valueObj = (JSONObject) incomingData.get(key);
                    int stepCount = -1;
                    try {
                        stepCount = Integer.parseInt(cKey);
                    } catch (NumberFormatException nfe) {
                        stepCount = (Integer) valueObj.get("position");
                    }

                    if (stepCount < 0) {
                        continue;
                    }

                    // We have a good step count, parse the value
                    BigDecimal duration = new BigDecimal(
                            valueObj.get("duration")
                            .toString().replace(",", "."));
                    BigDecimal temp = new BigDecimal(valueObj.get("temp")
                            .toString().replace(",", "."));

                    String method = valueObj.get("method").toString();
                    String type = valueObj.get("type").toString();

                    // Add the step
                    MashStep newStep = mControl.addMashStep(stepCount);
                    newStep.setDuration(duration);
                    newStep.setTemp(temp);
                    newStep.setMethod(method);
                    newStep.setType(type);
                    newStep.setTempUnit(tempUnit, false);

                    BrewServer.LOG.info(newStep.toString());
                } catch (NumberFormatException e) {
                    // Couldn't work out what this is
                    BrewServer.LOG.warning("Couldn't parse " + cKey);
                    BrewServer.LOG.warning(incomingData.get(key).toString());
                    e.printStackTrace();
                }
            }
            mControl.sortMashSteps();
            LaunchControl.startMashControl(pid);
        }
        return true;
    }

    /**
     * Add a mash step (and mash control if needed) to a PID.
     * @param params The incoming parameters.
     * @return The NanoHTTPD response to return to the browser.
     */
    private Response addMashStep(Map<String, String> params) {
        // Parse the response
        // Temp unit, PID, duration, temp, method, type, step number
        // Default to the existing temperature scale
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Add a new mashstep to the specified PID");
        usage.put("temp_unit (optional)",
            "The temperature unit for the mash step (optional, defaults to the system");
        usage.put("pid", "The PID to add the mash step to");
        usage.put("method", "The mash step method");
        usage.put("type", "The mash step type");
        usage.put("duration", "The time to hold the mash step for (in minutes)");
        usage.put("temp", "The temperature to set to the mash step to");
        usage.put("step", "The step number");
        params = ParseParams(params);
        Status status = Response.Status.OK;
        String tempUnit = LaunchControl.getScale();

        if (params.containsKey("temp_unit")) {
            tempUnit = params.get("temp_unit");
        }

        String pid = params.get("pid");
        String method = params.get("method");
        String type = params.get("type");

        try {
            BigDecimal duration = new BigDecimal(
                    params.get("duration").replace(",", "."));
            BigDecimal temp = new BigDecimal(
                    params.get("temp").replace(",", "."));
            int stepNumber = Integer.parseInt(params.get("step"));

            // Double check for any issues.
            if (pid == null) {
                throw new IllegalStateException(
                        "Couldn't add mashstep: No PID provided");
            }
            if (method == null) {
                throw new IllegalStateException(
                        "Couldn't add mashstep: No method provided");
            }
            if (type == null) {
                throw new IllegalStateException(
                        "Couldn't add mashstep: No type provided");
            }

            if (LaunchControl.findPID(pid) == null) {
                throw new IllegalStateException(
                        "Couldn't add mashstep: Couldn't find PID: " + pid);
            }

            MashControl mControl = LaunchControl.findMashControl(pid);

            if (mControl == null) {
                // Add a new MashControl to the list
                mControl = new MashControl();
                LaunchControl.addMashControl(mControl);
                mControl.setOutputControl(pid);
                LaunchControl.startMashControl(pid);
            }

            MashStep newStep = mControl.addMashStep(stepNumber);
            newStep.setMethod(method);
            newStep.setType(type);
            newStep.setDuration(duration);
            newStep.setTempUnit(tempUnit);
            newStep.setTemp(temp);

            mControl.sortMashSteps();

        } catch (NullPointerException nfe) {
            LaunchControl.setMessage(
                    "Couldn't add mashstep, problem parsing values: "
                        + params.toString() + nfe.getMessage());
            status = Response.Status.BAD_REQUEST;
        } catch (NumberFormatException nfe) {
            LaunchControl.setMessage(
                "Couldn't add mashstep, problem parsing values: "
                        + params.toString());
            status = Response.Status.BAD_REQUEST;
        } catch (IllegalStateException ise) {
            LaunchControl.setMessage(ise.getMessage());
            status = Response.Status.BAD_REQUEST;
        }

        return new Response(status, MIME_TYPES.get("json"),
                usage.toJSONString());
    }

    /**
     * Reorder the mash steps.
     * @param params The incoming params, check the usage.
     * @return The Response object.
     */
    private Response reorderMashProfile(final Map<String, String> params) {
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Set the order for the mash profile.");
        usage.put("pid", "The name of the PID to change the mash profile order on.");
        usage.put(":old=:new", "The old position and the new position");
        // Resort the mash profile for the PID, then sort the rest
        String pid = params.get("pid");
        // Do we have a PID coming in?
        if (pid == null) {
            LaunchControl.setMessage("No PID supplied for mash profile order sort");
            return new Response(Status.BAD_REQUEST, MIME_TYPES.get("json"),
                    usage.toJSONString());
        }
        // Prevent any errors from the PID not being a number.
        params.remove("pid");
        // Do we have a mash control for the PID?
        MashControl mControl = LaunchControl.findMashControl(pid);
        if (mControl == null) {
            LaunchControl.setMessage("No Mash specified for the PID supplied " + pid);
            return new Response(Status.BAD_REQUEST, MIME_TYPES.get("json"),
                    usage.toJSONString());
        }

        // Should be good to go, iterate and update!
        for (Map.Entry<String, String> mEntry: params.entrySet()) {
            if (mEntry.getKey().equals("NanoHttpd.QUERY_STRING")) {
                continue;
            }
            try {
                int oldPos = Integer.parseInt(mEntry.getKey());
                int newPos = Integer.parseInt(mEntry.getValue());
                mControl.getMashStep(oldPos).setPosition(newPos);
            } catch (NumberFormatException nfe) {
                LaunchControl.setMessage("Failed to parse Mash reorder value, things may get weird" + mEntry.getKey() + ": " + mEntry.getValue());
            } catch (IndexOutOfBoundsException ie) {
                LaunchControl.setMessage("Invalid mash position, things may get weird");
            }
        }
        mControl.sortMashSteps();
        return new Response(Status.OK, MIME_TYPES.get("json"),
                usage.toJSONString());
    }

    /**
     * Delete the specified mash step.
     * @param params The details.
     * @return The Response.
     */
    private Response delMashStep(Map<String, String> params) {
        // Parse the response
        // Temp unit, PID, duration, temp, method, type, step number
        // Default to the existing temperature scale
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Add a new mashstep to the specified PID");
        usage.put("pid", "The PID to delete the mash step from");
        usage.put("position", "The mash step to delete");
        Status status = Status.OK;

        try {
            String pid = params.get("pid");
            int position = Integer.parseInt(params.get("position"));
            MashControl mControl = LaunchControl.findMashControl(pid);

            if (mControl == null) {
                // Add a mash control
                if (LaunchControl.findPID(pid) == null) {
                    LaunchControl.setMessage(
                            "Could not find the specified PID: " + pid);
                } else {
                    mControl = new MashControl();
                    mControl.setOutputControl(pid);
                }
            }

            if (mControl != null) {
                mControl.delMashStep(position);
            } else {
                status = Status.BAD_REQUEST;
            }
        } catch (NumberFormatException nfe) {
            LaunchControl.setMessage(
                    "Couldn't parse the position to delete: " + params);
            status = Status.BAD_REQUEST;
        } catch (NullPointerException ne) {
            LaunchControl.setMessage(
                    "Couldn't parse the mash data to delete: " + params);
            status = Status.BAD_REQUEST;
        } catch (IndexOutOfBoundsException ie) {
            LaunchControl.setMessage(
                    "Invalid Mash step position to delete: " + ie.getMessage());
            status = Status.BAD_REQUEST;
        }

        return new Response(status, MIME_TYPES.get("json"),
                usage.toJSONString());
    }

    /**
     * Toggle the state of the mash profile on/off.
     * @param parameters
     *            The parameters from the original request.
     * @return True if set, false if there's an error
     */
    private boolean toggleMashProfile(final Map<String, String> parameters) {

        String pid = null;
        if (parameters.containsKey("pid")) {
            pid = parameters.get("pid");
        } else {
            BrewServer.LOG.warning("No PID provided to toggle Mash Profile");
            return false;
        }

        boolean activate = false;
        if (parameters.containsKey("status")) {
            if (parameters.get("status").equalsIgnoreCase("activate")) {
                activate = true;
            }
        } else {
            BrewServer.LOG.warning("No Status provided to toggle Mash Profile");
            return false;
        }

        int position = -1;
        if (parameters.containsKey("position")) {
            try {
                position = Integer.parseInt(parameters.get("position"));
            } catch (NumberFormatException e) {
                BrewServer.LOG.warning("Couldn't parse positional argument: "
                        + parameters.get("position"));
                return false;
            }
        }

        MashControl mObj = LaunchControl.findMashControl(pid);

        if (mObj == null) {
            return false;
        }

        MashStep mashEntry = mObj.getCurrentMashStep();

        // No active mash step
        int stepToUse = -1;
        if (mashEntry == null) {
            if (position >= 0) {
                stepToUse = position;
                BrewServer.LOG.warning("Using initial mash step for " + pid
                        + " at position " + position);
            } else {
                // No position wanted, activate the first step
                stepToUse = 0;
                BrewServer.LOG.warning("Using initial mash step for " + pid);
            }
        } else {
            // We have a mash step position
            if (position >= 0) {
                stepToUse = position;
                BrewServer.LOG.warning("Using mash step for " + pid
                        + " at position " + position);
            }

            if (!activate) {
                // We're de-activating everything
                stepToUse = -1;
            } else {
                BrewServer.LOG.warning("A mash is in progress for " + pid
                        + " but no positional argument is set");
                return false;
            }
        }

        if (stepToUse >= 0 && activate) {
            mObj.activateStep(stepToUse);
            BrewServer.LOG
                    .warning("Activated " + pid + " step at " + stepToUse);
        } else {
            mObj.deactivateStep(stepToUse);
            BrewServer.LOG.warning("Deactivated " + pid + " step at "
                    + stepToUse);
        }

        return true;
    }

    /**
     * Read the incoming parameters and edit the vessel as appropriate.
     *
     * @param params
     *            The parameters from the client.
     * @return True is success, false if failure.
     */
    private Response editVessel(final Map<String, String> params) {
        String auxpin = "", newName = "", heatgpio = "";
        String inputUnit = "", cutoff = "", coolgpio = "";
        String calibration = null;
        boolean heatInvert = false, coolInvert = false;

        Set<Entry<String, String>> incomingParams = params.entrySet();
        Map<String, String> parms;
        Iterator<Entry<String, String>> it = incomingParams.iterator();
        Entry<String, String> param = null;
        JSONObject incomingData = null;
        JSONParser parser = new JSONParser();
        String errorMsg = "No Changes Made";

        // Try to Parse JSON Data
        while (it.hasNext()) {
            param = it.next();
            BrewServer.LOG.info("Key: " + param.getKey());
            BrewServer.LOG.info("Entry: " + param.getValue());
            try {
                Object parsedData = parser.parse(param.getValue());
                if (parsedData instanceof JSONArray) {
                    incomingData = (JSONObject) ((JSONArray) parsedData).get(0);
                } else {
                    incomingData = (JSONObject) parsedData;
                    inputUnit = param.getKey();
                }
            } catch (Exception e) {
                BrewServer.LOG.info("couldn't read " + param.getValue()
                        + " as a JSON Value " + e.getMessage());
            }
        }

        if (incomingData != null) {
            // Use the JSON Data
            BrewServer.LOG.info("Found valid data for " + inputUnit);
            parms = (Map<String, String>) incomingData;
        } else {
            inputUnit = params.get("form");
            parms = params;
        }

        // Fall back to the old style
        if (parms.containsKey("new_name")) {
            newName = parms.get("new_name");
        }

        if (parms.containsKey("new_heat_gpio")) {
            heatgpio = parms.get("new_heat_gpio");
        }

        if (parms.containsKey("new_cool_gpio")) {
            coolgpio = parms.get("new_cool_gpio");
        }

        if (parms.containsKey("heat_invert")) {
            heatInvert = parms.get("heat_invert").equals("on");
        }

        if (parms.containsKey("cool_invert")) {
            coolInvert = parms.get("cool_invert").equals("on");
        }

        if (parms.containsKey("auxpin")) {
            auxpin = parms.get("auxpin");
        }

        if (parms.containsKey("cutoff")) {
            cutoff = parms.get("cutoff");
        }

        if (parms.containsKey("calibration")) {
            calibration = parms.get("calibration");
        }

        if (inputUnit.equals("")) {
            BrewServer.LOG.warning("No Valid input unit");
            errorMsg = "No Valid Input Unit";
        }

        Temp tProbe = LaunchControl.findTemp(inputUnit);
        PID tPID = LaunchControl.findPID(inputUnit);

        if (tProbe == null) {
            inputUnit = inputUnit.replace("_", " ");
            tProbe = LaunchControl.findTemp(inputUnit);
            tPID = LaunchControl.findPID(inputUnit);
        }

        if (tProbe == null) {
            LaunchControl.setMessage("Couldn't find PID: " + inputUnit);
        }

        if (tProbe != null && !newName.equals("")) {
            tProbe.setName(newName);
            BrewServer.LOG.warning("Updated temp name " + newName);
        }

        if (!cutoff.equals("")) {
            tProbe.setCutoffTemp(cutoff);
        }

        if (calibration != null) {
            tProbe.setCalibration(calibration);
        }

        if (tPID != null && !newName.equals("")) {
            tPID.getTemp().setName(newName);
            BrewServer.LOG.warning("Updated PID Name" + newName);
        }

        if (newName.equals("")) {
            newName = inputUnit;
        }

        // HEATING GPIO
        if (!heatgpio.equals("") || !coolgpio.equals("")) {
            // The GPIO is Set.
            if (tPID == null) {
                // No PID already, create one.
                tPID = new PID(tProbe, newName);
                // Setup the heating output
                if (!heatgpio.equals("")) {
                    tPID.setHeatGPIO(heatgpio);
                    tPID.setHeatInverted(heatInvert);
                }
                // Setup the cooling output
                if (!coolgpio.equals("")) {
                    tPID.setCoolGPIO(coolgpio);
                    tPID.setCoolInverted(coolInvert);
                }
                tPID.setAux(auxpin);
                LaunchControl.addPID(tPID);
                BrewServer.LOG.warning("Create PID");
                return new Response(Status.OK, MIME_TYPES.get("txt"),
                        "PID Added");
            } else {
                if (!heatgpio.equals(tPID.getHeatGPIO())) {
                    // We have a PID, set it to the new value
                    tPID.setHeatGPIO(heatgpio);
                }
                tPID.setHeatInverted(heatInvert);
                if (!coolgpio.equals(tPID.getCoolGPIO())) {
                    // We have a PID, set it to the new value
                    tPID.setCoolGPIO(coolgpio);
                }
                tPID.setCoolInverted(coolInvert);
                return new Response(Status.OK, MIME_TYPES.get("txt"),
                        "PID Updated");
            }
        } else {
            if (tPID != null) {
                LaunchControl.deletePID(tPID);
                tPID.setHeatGPIO("");
            }
        }

        JSONObject usage = new JSONObject();
        usage.put("Usage", "Add or update a Temperature probe to the system,"
                + " incoming object"
                + " should be a JSON Literal of \nold_name: {details}");
        usage.put("new_name", "The name of the Temperature Probe to add");
        usage.put("new_gpio", "The GPIO for the PID to work on");
        usage.put("aux_gpio", "The Auxilliary GPIO for the PID to work on");
        usage.put("Error", "Invalid parameters passed " + errorMsg + " = "
                + params.toString());

        return new Response(Status.BAD_REQUEST, MIME_TYPES.get("json"),
                usage.toJSONString());

    }

    /**
     * Add a new timer to the brewery.
     * 
     * @param params
     *            The parameter list
     * @return True if added ok
     */
    private Response addTimer(final Map<String, String> params) {
        String oldName = "", newName = "", gpio = "";
        String inputUnit = "";

        Set<Entry<String, String>> incomingParams = params.entrySet();
        Map<String, String> parms;
        Iterator<Entry<String, String>> it = incomingParams.iterator();
        Entry<String, String> param = null;
        JSONObject incomingData = null;
        JSONParser parser = new JSONParser();

        // Try to Parse JSON Data
        while (it.hasNext()) {
            param = it.next();
            BrewServer.LOG.info("Key: " + param.getKey());
            BrewServer.LOG.info("Entry: " + param.getValue());
            try {
                Object parsedData = parser.parse(param.getValue());
                if (parsedData instanceof JSONArray) {
                    incomingData = (JSONObject) ((JSONArray) parsedData).get(0);
                } else {
                    incomingData = (JSONObject) parsedData;
                    inputUnit = param.getKey();
                }
            } catch (Exception e) {
                BrewServer.LOG.info("couldn't read " + param.getValue()
                        + " as a JSON Value " + e.getMessage());
            }
        }

        if (incomingData != null) {
            // Use the JSON Data
            BrewServer.LOG.info("Found valid data for " + inputUnit);
            parms = (Map<String, String>) incomingData;
        } else {
            inputUnit = params.get("form");
            parms = params;
        }

        // Fall back to the old style
        if (parms.containsKey("new_name")) {
            newName = parms.get("new_name");
        }

        if (LaunchControl.addTimer(newName, "")) {
            return new Response(Status.OK, MIME_TYPES.get("txt"), "Timer Added");
        }

        JSONObject usage = new JSONObject();
        usage.put("Usage", "Add a new pump to the system.");
        usage.put("new_name", "The name of the timer to add");
        usage.put("mode",
                "The mode for the timer, increment, or decrement (optional)");
        usage.put("Error", "Invalid parameters passed " + params.toString());

        return new Response(Status.BAD_REQUEST, MIME_TYPES.get("json"),
                usage.toJSONString());
    }

    /**
     * Add a new pump to the brewery.
     * 
     * @param params
     *            The parameter list
     * @return True if added ok
     */
    private Response addPump(final Map<String, String> params) {
        String newName = "", gpio = "";
        String inputUnit = "";
        boolean invert = false;

        Set<Entry<String, String>> incomingParams = params.entrySet();
        Map<String, String> parms;
        Iterator<Entry<String, String>> it = incomingParams.iterator();
        Entry<String, String> param = null;
        JSONObject incomingData = null;
        JSONParser parser = new JSONParser();

        // Try to Parse JSON Data
        while (it.hasNext()) {
            param = it.next();
            BrewServer.LOG.info("Key: " + param.getKey());
            BrewServer.LOG.info("Entry: " + param.getValue());
            try {
                Object parsedData = parser.parse(param.getValue());
                if (parsedData instanceof JSONArray) {
                    incomingData = (JSONObject) ((JSONArray) parsedData).get(0);
                } else {
                    incomingData = (JSONObject) parsedData;
                    inputUnit = param.getKey();
                }
            } catch (Exception e) {
                BrewServer.LOG.info("couldn't read " + param.getValue()
                        + " as a JSON Value " + e.getMessage());
            }
        }

        if (incomingData != null) {
            // Use the JSON Data
            BrewServer.LOG.info("Found valid data for " + inputUnit);
            parms = (Map<String, String>) incomingData;
        } else {
            inputUnit = params.get("form");
            parms = params;
        }

        // Fall back to the old style
        if (parms.containsKey("new_name")) {
            newName = parms.get("new_name");
        }

        if (parms.containsKey("new_gpio")) {
            gpio = parms.get("new_gpio");
        }

        if (parms.containsKey("invert")) {
            invert = parms.get("invert").equals("on");
        }

        if (LaunchControl.addPump(newName, gpio)) {
            LaunchControl.findPump(newName).setInverted(invert);
            return new Response(Status.OK, MIME_TYPES.get("txt"), "Pump Added");
        } else {
            LaunchControl.setMessage(
                    "Could not add pump " + newName + ": " + gpio);
        }

        JSONObject usage = new JSONObject();
        usage.put("Usage", "Add a new pump to the system");
        usage.put("new_name", "The name of the pump to add");
        usage.put("new_gpio", "The GPIO for the pump to work on");
        usage.put("Error", "Invalid parameters passed " + params.toString());

        return new Response(Status.BAD_REQUEST, MIME_TYPES.get("json"),
                usage.toJSONString());
    }

    /**
     * Read the incoming parameters and update the PID as appropriate.
     * 
     * @param params
     *            The parameters from the client
     * @return True if success, false if failure
     */
    @SuppressWarnings("unchecked")
    private Response updatePID(final Map<String, String> params) {
        String temp, mode = "off";
        BigDecimal dTemp = new BigDecimal(0), duty = new BigDecimal(0),
                heatcycle = new BigDecimal(0), setpoint = new BigDecimal(0),
                heatp = new BigDecimal(0), heati = new BigDecimal(0),
                heatd = new BigDecimal(0), min = new BigDecimal(0),
                max = new BigDecimal(0), time = new BigDecimal(0),
                coolcycle = new BigDecimal(0), coolp= new BigDecimal(0),
                cooli = new BigDecimal(0), coold = new BigDecimal(0),
                cooldelay = new BigDecimal(0), cycle = new BigDecimal(0);

        JSONObject sub_usage = new JSONObject();
        Map<String, String> parms = ParseParams(params);
        String inputUnit = parms.get("inputunit");
        boolean errorValue = false;
        PID tPID = LaunchControl.findPID(inputUnit);
        if (tPID == null) {
            BrewServer.LOG.warning("Couldn't find PID: " + inputUnit);
            LaunchControl.setMessage("Could not find PID: " + inputUnit);
            LaunchControl.setMessage("Bad inputs when updating."
                    + " Please check the system log");
            return new Response(Status.BAD_REQUEST, MIME_TYPES.get("json"),
                    "");
        }

        // Fall back to the old style
        sub_usage.put("dutycycle", "The new duty cycle % to set");
        if (parms.containsKey("dutycycle")) {
            temp = parms.get("dutycycle");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                duty = dTemp;
                BrewServer.LOG.info("Duty cycle: " + duty);
            } catch (NumberFormatException nfe) {
                System.out.print("Bad duty");
                errorValue = true;
            }
        }

        sub_usage.put("setpoint", "The new target temperature to set");
        if (parms.containsKey("setpoint")) {
            temp = parms.get("setpoint");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                setpoint = dTemp;
                BrewServer.LOG.info("Set Point: " + setpoint);
            } catch (NumberFormatException nfe) {
                BrewServer.LOG.warning("Bad setpoint");
                errorValue = true;
            }
        }

        sub_usage.put("heatcycletime", "The new heat cycle time in seconds to set");
        if (parms.containsKey("heatcycletime")) {
            temp = parms.get("heatcycletime");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                heatcycle = dTemp;
                BrewServer.LOG.info("Cycle time: " + heatcycle);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidHeater()) {
                    BrewServer.LOG.warning("Bad cycle");
                    errorValue = true;
                }
            }
        }

        sub_usage.put("heatp", "The new proportional value to set");
        if (parms.containsKey("heatp")) {
            temp = parms.get("heatp");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                heatp = dTemp;
                BrewServer.LOG.info("heat P: " + heatp);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidHeater()) {
                    BrewServer.LOG.warning("Bad heat p");
                    errorValue = true;
                }
            }
        }

        sub_usage.put("heati", "The new integral value to set");
        if (parms.containsKey("heati")) {
            temp = parms.get("heati");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                heati = dTemp;
                BrewServer.LOG.info("Heat I: " + heati);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidHeater()) {
                    BrewServer.LOG.warning("Bad heat i");
                    errorValue = true;
                }
            }
        }

        sub_usage.put("heatd", "The new head differential value to set");
        if (parms.containsKey("heatd")) {
            temp = parms.get("heatd");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                heatd = dTemp;
                BrewServer.LOG.info("Heat D: " + heatd);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidHeater()) {
                    BrewServer.LOG.warning("Bad heat d");
                    errorValue = true;
                }
            }
        }

        sub_usage.put("coolcycletime", "The new cool cycle time in seconds to set");
        if (parms.containsKey("coolcycletime")) {
            temp = parms.get("coolcycletime");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                coolcycle = dTemp;
                BrewServer.LOG.info("Cycle time: " + coolcycle);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidCooler()) {
                    BrewServer.LOG.warning("Bad cycle");
                    errorValue = true;
                }
            }
        }
        
        sub_usage.put("cycletime", "The new manual cycle time in seconds to set");
        if (parms.containsKey("cycletime")) {
            temp = parms.get("cycletime");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                cycle = dTemp;
                BrewServer.LOG.info("Cycle time: " + cycle);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidHeater()) {
                    BrewServer.LOG.warning("Bad cycle");
                    errorValue = true;
                }
            }
        }

        sub_usage.put("cooldelay", "The new cool cycle delay in minutes to set");
        if (parms.containsKey("cooldelay")) {
            temp = parms.get("cooldelay");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                cooldelay = dTemp;
                BrewServer.LOG.info("Delay time: " + cooldelay);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidHeater()) {
                    BrewServer.LOG.warning("Bad Cool delay");
                    errorValue = true;
                }
            }
        }

        sub_usage.put("coolp", "The new proportional value to set");
        if (parms.containsKey("coolp")) {
            temp = parms.get("coolp");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                coolp = dTemp;
                BrewServer.LOG.info("cool P: " + coolp);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidCooler()) {
                    BrewServer.LOG.warning("Bad cool p");
                    errorValue = true;
                }
            }
        }

        sub_usage.put("cooli", "The new integral value to set");
        if (parms.containsKey("cooli")) {
            temp = parms.get("cooli");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                cooli = dTemp;
                BrewServer.LOG.info("Heat I: " + cooli);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidCooler()) {
                    BrewServer.LOG.warning("Bad cool i");
                    errorValue = true;
                }
            }
        }

        sub_usage.put("coold", "The new head differential value to set");
        if (parms.containsKey("coold")) {
            temp = parms.get("coold");
            try {
                dTemp = new BigDecimal(temp.replace(",", "."));
                coold = dTemp;
                BrewServer.LOG.info("Heat D: " + coold);
            } catch (NumberFormatException nfe) {
                if (tPID.hasValidCooler()) {
                    BrewServer.LOG.warning("Bad cool d");
                    errorValue = true;
                }
            }
        }

        sub_usage.put("mode", "The new mode to set");
        if (parms.containsKey("mode")) {
            mode = parms.get("mode");
            BrewServer.LOG.info("Mode: " + mode);
        }

        sub_usage.put("min",
                "The minimum temperature to enable the output (HYSTERIA)");
        if (parms.containsKey("min")) {
            try {
                dTemp = new BigDecimal(parms.get("min").replace(",", "."));
                min = dTemp;
                BrewServer.LOG.info("Min: " + mode);
            } catch (NumberFormatException nfe) {
                BrewServer.LOG.warning("Bad minimum");
                errorValue = true;
            }
        }

        sub_usage.put("max",
                "The maximum temperature to disable the output (HYSTERIA)");
        if (parms.containsKey("max")) {
            try {
                dTemp = new BigDecimal(parms.get("max").replace(",", "."));
                max = dTemp;
                BrewServer.LOG.info("Max: " + mode);
            } catch (NumberFormatException nfe) {
                BrewServer.LOG.warning("Bad maximum");
                errorValue = true;
            }
        }

        sub_usage.put("time",
                "The minimum time when enabling the output (HYSTERIA)");
        if (parms.containsKey("time")) {
            try {
                dTemp = new BigDecimal(parms.get("time").replace(",", "."));
                time = dTemp;
                BrewServer.LOG.info("Time: " + time);
            } catch (NumberFormatException nfe) {
                BrewServer.LOG.warning("Bad time");
                errorValue = true;
            }
        }

        BrewServer.LOG.info("Form: " + inputUnit);

        JSONObject usage = new JSONObject();
        usage.put(":PIDname", sub_usage);

        if (errorValue) {
            LaunchControl.setMessage("Bad inputs when updating. Please check the system log");
            return new Response(Status.BAD_REQUEST, MIME_TYPES.get("json"),
                    usage.toJSONString()); 
        }
        
        if (tPID != null) {
            if (mode.equalsIgnoreCase("hysteria")) {
                tPID.setHysteria(min, max, time);
                tPID.useHysteria();
            } else {
                BrewServer.LOG.info(mode + ":" + duty + ":" + heatcycle + ":"
                        + setpoint + ":" + heatp + ":" + heati + ":" + heatd);
                tPID.updateValues(mode, duty, heatcycle, setpoint, heatp, heati, heatd);
                tPID.setCoolCycle(coolcycle);
                tPID.setCoolP(coolp);
                tPID.setCoolI(cooli);
                tPID.setCoolD(coold);
                tPID.setCoolDelay(cooldelay);
                
                if (mode.equalsIgnoreCase("manual")) {
                    tPID.setManualCycle(cycle);
                }
            }
            return new Response(Status.OK, MIME_HTML, "PID " + inputUnit
                    + " updated");
        } else {
            BrewServer.LOG.warning("Attempted to update a non existent PID, "
                    + inputUnit + ". Please check your client");
            usage.put("Error", "Non existent PID specified: " + inputUnit);
            return new Response(Status.BAD_REQUEST, MIME_TYPES.get("json"),
                    usage.toJSONString());
        }

    }

    /**
     * The main method that checks the data coming into the server.
     * 
     * @param uri
     *            The URI requested
     * @param method
     *            The type of the request (GET/POST/DELETE)
     * @param header
     *            The header map from the request
     * @param parms
     *            The incoming Parameter map.
     * @param files
     *            A map of incoming files.
     * @return A NanoHTTPD Response Object
     */
    public final Response serve(final String uri, final Method method,
            final Map<String, String> header, final Map<String, String> parms,
            final Map<String, String> files) {

        BrewServer.LOG.info("URL : " + uri + " method: " + method);

        if (uri.equalsIgnoreCase("/clearStatus")) {
            LaunchControl.setMessage("");
            return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                    "Status Cleared");
        }

        // parms contains the properties here
        if (uri.equalsIgnoreCase("/mashprofile")) {
            if (updateMashProfile(parms)) {
                return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                        "Updated MashProfile");
            }

            return new NanoHTTPD.Response(Status.BAD_REQUEST, MIME_HTML,
                    "Failed to update Mashprofile");
        }

        if (uri.equalsIgnoreCase("/addmashstep")) {
            return addMashStep(parms);
        }

        if (uri.equalsIgnoreCase("/addsystem")) {
            LaunchControl.addSystemTemp();
            return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                    "Added system temperature");
        }
        
        if (uri.equalsIgnoreCase("/delsystem")) {
            LaunchControl.delSystemTemp();
            return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                    "Deleted system temperature");
        }

        if (uri.equalsIgnoreCase("/delmashstep")) {
            return delMashStep(parms);
        }

        if (uri.equalsIgnoreCase("/togglemash")) {
            if (toggleMashProfile(parms)) {
                return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                        "Toggled mash profile");
            }
            return new NanoHTTPD.Response(Status.BAD_REQUEST, MIME_HTML,
                    "Failed to toggle MashProfile");
        }

        if (uri.equalsIgnoreCase("/reordermashprofile")) {
            return reorderMashProfile(parms);
        }

        if (uri.equalsIgnoreCase("/editdevice")) {
            return editVessel(parms);
        }
        if (uri.equalsIgnoreCase("/updatepid")) {
            // parse the values if possible
            return updatePID(parms);
        }

        if (uri.equalsIgnoreCase("/updateday")) {
            // we're storing the data for the brew day
            String tempDateStamp;
            BrewDay brewDay = LaunchControl.getBrewDay();

            // updated date
            if (parms.containsKey("updated")) {
                tempDateStamp = parms.get("updated");
                brewDay.setUpdated(tempDateStamp);
            } else {
                // we don't have an updated datestamp
                return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                        "No update datestamp, not updating a thang! YA HOSER!");
            }

            Iterator<Entry<String, String>> it = parms.entrySet().iterator();
            Entry<String, String> e = null;

            while (it.hasNext()) {
                e = it.next();

                if (e.getKey().endsWith("Start")) {
                    int trimEnd = e.getKey().length() - "Start".length();
                    String name = e.getKey().substring(0, trimEnd);
                    brewDay.startTimer(name, e.getValue());
                } else if (e.getKey().endsWith("End")) {
                    int trimEnd = e.getKey().length() - "End".length();
                    String name = e.getKey().substring(0, trimEnd);
                    brewDay.stopTimer(name, e.getValue());
                } else if (e.getKey().endsWith("Reset")) {
                    int trimEnd = e.getKey().length() - "Reset".length();
                    String name = e.getKey().substring(0, trimEnd);
                    brewDay.resetTimer(name);
                }
            }

            return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                    "Updated Brewday");
        }

        if (uri.equalsIgnoreCase("/updatepump")) {
            if (parms.containsKey("toggle")) {
                String pumpname = parms.get("toggle");
                Pump tempPump = LaunchControl.findPump(pumpname.replaceAll("_", " "));
                if (tempPump != null) {
                    if (tempPump.getStatus()) {
                        tempPump.turnOff();
                    } else {
                        tempPump.turnOn();
                    }

                    return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                            "Updated Pump");
                } else {
                    JSONObject usage = new JSONObject();
                    usage.put("Error", "Invalid name supplied: " + pumpname);
                    usage.put("toggle", "The name of the Pump to toggle on/off");
                    return new Response(Status.BAD_REQUEST,
                            MIME_TYPES.get("txt"), "Invalid pump: " + pumpname
                                    + " provided.");
                }
            }
        }

        if (uri.equalsIgnoreCase("/toggleaux")) {
            if (parms.containsKey("toggle")) {
                String pidname = parms.get("toggle");
                PID tempPID = LaunchControl.findPID(pidname);
                if (tempPID != null) {
                    tempPID.toggleAux();
                    return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                            "Updated Aux for " + pidname);
                } else {
                    LOG.warning("Invalid PID: " + pidname + " provided.");
                    JSONObject usage = new JSONObject();
                    usage.put("Error", "Invalid name supplied: " + pidname);
                    usage.put("toggle",
                            "The name of the PID to toggle the aux output for");
                    return new Response(usage.toJSONString());
                }
            }
        }

        if (uri.equalsIgnoreCase("/getstatus")) {
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    LaunchControl.getJSONStatus());
        }

        if (uri.equalsIgnoreCase("/getsystemsettings")) {
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    LaunchControl.getSystemStatus());
        }

        if (uri.equalsIgnoreCase("/updatesystemsettings")) {
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    updateSystemSettings(parms));
        }

        if (uri.equalsIgnoreCase("/oldcontroller")) {
            return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                    LaunchControl.getControlPage());
        }

        if (uri.equalsIgnoreCase("/controller")) {
            RenderHTML renderController = new RenderHTML();
            HtmlCanvas html = new HtmlCanvas();
            String result = "";
            try {
                renderController.renderOn(html);
                result = html.toHtml();
            } catch (IOException e) {
                e.printStackTrace();
                result = e.getMessage();
            }
            return new NanoHTTPD.Response(Status.OK, MIME_HTML,
                    result);
        }

        if (uri.equalsIgnoreCase("/timers")) {
            return new NanoHTTPD.Response(Status.OK, MIME_HTML, LaunchControl
                    .getBrewDay().brewDayStatus().toString());
        }

        if (uri.equalsIgnoreCase("/graph")) {
            return serveFile("/templates/static/graph/graph.html", header,
                    rootDir);
        }

        if (uri.toLowerCase().startsWith("/graph-data")) {
            return getGraphData(parms);
        }

        if (uri.equalsIgnoreCase("/addpump")) {
            return addPump(parms);
        }

        if (uri.equalsIgnoreCase("/addtimer")) {
            return addTimer(parms);
        }

        if (uri.equalsIgnoreCase("/addvolpoint")) {
            return addVolumePoint(parms);
        }

        if (uri.equalsIgnoreCase("/checkgit")) {
            LaunchControl.checkForUpdates();
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    "{Status:'OK'}");
        }

        if (uri.equalsIgnoreCase("/restartupdate")) {
            LaunchControl.updateFromGit();
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    "{Status:'OK'}");
        }

        if (uri.equalsIgnoreCase("/setbreweryname")) {
            updateBreweryName(parms);
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    "{Status:'OK'}");
        }

        if (uri.equalsIgnoreCase("/settheme")) {
            String newTheme = parms.get("name");

            if (newTheme == null) {
                return new NanoHTTPD.Response(Status.BAD_REQUEST,
                        MIME_TYPES.get("json"),
                        "{Status:'No name provided'}");
            }

            String fileName = "/logos/" + newTheme + ".ico";
            if (!(new File(rootDir, fileName).exists())) {
                // It doesn't exist
                LaunchControl.setMessage("Favicon for the new theme: "
                        + newTheme + ", doesn't exist."
                        + " Please add: " + fileName + " and try again");
                return new NanoHTTPD.Response(Status.BAD_REQUEST,
                        MIME_TYPES.get("json"),
                        "{Status:'Favicon doesn\'t exist'}");
            }

            fileName = "/logos/" + newTheme + ".gif";
            if (!(new File(rootDir, fileName).exists())) {
                // It doesn't exist
                LaunchControl.setMessage("Brewry image for the new theme: "
                        + newTheme + ", doesn't exist."
                        + " Please add: " + fileName + " and try again");
                return new NanoHTTPD.Response(Status.BAD_REQUEST,
                        MIME_TYPES.get("json"),
                        "{Status:'Brewery Image doesn\'t exist'}");
            }

            LaunchControl.theme = newTheme;
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    "{Status:'OK'}");

        }

        if (uri.equalsIgnoreCase("/favicon.ico")) {
            // Has the favicon been overridden?
            // Check to see if there's a theme set.
            if (LaunchControl.theme != null
                    && !LaunchControl.theme.equals("")) {
                if (new File(rootDir,
                        "/logos/" + LaunchControl.theme + ".ico").exists()) {
                    return serveFile("/logos/" + LaunchControl.theme + ".ico",
                            header, rootDir);
                }
            }

            if (new File(rootDir, uri).exists()) {
                return serveFile(uri, header, rootDir);
            }

        }

        // NLS Support
        if (uri.startsWith("/nls/")) {
            return serveFile(uri.replace("/nls/", "/src/com/sb/elsinore/nls/"),
                header, rootDir);
        }

        if (uri.equalsIgnoreCase("/brewerImage.gif")) {
            // Has the user uploaded a file?
            if (new File(rootDir, uri).exists()) {
                return serveFile(uri, header, rootDir);
            }
            // Check to see if there's a theme set.
            if (LaunchControl.theme != null
                    && !LaunchControl.theme.equals("")) {
                if (new File(rootDir,
                        "/logos/" + LaunchControl.theme + ".gif").exists()) {
                    return serveFile("/logos/" + LaunchControl.theme + ".gif",
                            header, rootDir);
                }
            }


        }

        if (!uri.equals("") && new File(rootDir, uri).exists()) {
            return serveFile(uri, header, rootDir);
        }

        if (uri.equalsIgnoreCase("/uploadimage")) {
            return updateBreweryImage(files);
        }

        if (uri.equalsIgnoreCase("/updatepumporder")) {
            return updatePumpOrder(parms);
        }

        if (uri.equalsIgnoreCase("/deletepump")) {
            return deletePump(parms);
        }

        if (uri.equalsIgnoreCase("/unlockpage")) {
            LaunchControl.unlockPage();
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    "{status: 'unlocked'}");
        }

        if (uri.equalsIgnoreCase("/lockpage")) {
            LaunchControl.lockPage();
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    "{status: 'locked'}");
        }

        if (uri.equalsIgnoreCase("/updatetimerorder")) {
            return updateTimerOrder(parms);
        }

        if (uri.equalsIgnoreCase("/deletetimer")) {
            return deleteTimer(parms);
        }

        if (uri.equalsIgnoreCase("/setscale")) {
            return setScale(parms);
        }

        if (uri.equalsIgnoreCase("/toggledevice")) {
            return toggleDevice(parms);
        }

        if (uri.equalsIgnoreCase("/stop")) {
            System.exit(128);
        }

        if (uri.equalsIgnoreCase("/setgravity")) {
            return setGravity(parms);
        }

        BrewServer.LOG.info("Unidentified URL: " + uri);
        JSONObject usage = new JSONObject();
        usage.put("controller", "Get the main controller page");
        usage.put("getstatus", "Get the current status as a JSON object");
        usage.put("timers", "Get the current timer status");

        usage.put("addpump", "Add a new pump");
        usage.put("addtimer", "Add a new timer");
        usage.put("addvolpoint", "Add a new volume point");

        usage.put("toggleaux", "toggle an aux output");
        usage.put("mashprofile", "Set a mash profile for the output");
        usage.put("editdevice", "Edit the settings on a device");

        usage.put("updatepid", "Update the PID Settings");
        usage.put("updateday", "Update the brewday information");
        usage.put("updatepump", "Change the pump status off/on");

        BrewServer.LOG.info("Invalid URI: " + uri);
        return new NanoHTTPD.Response(Status.NOT_FOUND, MIME_TYPES.get("json"),
                usage.toJSONString());
    }

    /**
     * Serves file from homeDir and its' subdirectories (only). Uses only URI,
     * ignores all headers and HTTP parameters.
     * 
     * @param incomingUri
     *            The URI requested
     * @param header
     *            The headers coming in
     * @param homeDir
     *            The root directory.
     * @return A NanoHTTPD Response for the file
     */
    public Response serveFile(final String incomingUri,
            final Map<String, String> header, final File homeDir) {
        Response res = null;
        String uri = incomingUri;

        // Make sure we won't die of an exception later
        if (!homeDir.isDirectory()) {
            res = new Response(Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT, "INTERNAL ERRROR: serveFile(): "
                            + "given homeDir is not a directory.");
        }

        if (res == null) {
            // Remove URL arguments
            uri = uri.trim().replace(File.separatorChar, '/');
            if (uri.indexOf('?') >= 0) {
                uri = uri.substring(0, uri.indexOf('?'));
            }

            // Prohibit getting out of current directory
            if (uri.startsWith("src/main") || uri.endsWith("src/main")
                    || uri.contains("../")) {
                res = new Response(Response.Status.FORBIDDEN,
                        NanoHTTPD.MIME_PLAINTEXT,
                        "FORBIDDEN: Won't serve ../ for security reasons.");
            }
        }

        File f = new File(homeDir, uri);
        if (res == null && !f.exists()) {
            res = new Response(Response.Status.NOT_FOUND,
                    NanoHTTPD.MIME_PLAINTEXT, "Error 404, file not found.");
        }

        // List the directory, if necessary
        if (res == null && f.isDirectory()) {
            // Browsers get confused without '/' after the
            // directory, send a redirect.
            if (!uri.endsWith("/")) {
                uri += "/";
                res = new Response(Response.Status.REDIRECT,
                        NanoHTTPD.MIME_HTML,
                        "<html><body>Redirected: <a href=\"" + uri + "\">"
                                + uri + "</a></body></html>");
                res.addHeader("Location", uri);
            }

            if (res == null) {
                // First try index.html and index.htm
                if (new File(f, "index.html").exists()) {
                    f = new File(homeDir, uri + "/index.html");
                } else if (new File(f, "index.htm").exists()) {
                    f = new File(homeDir, uri + "/index.htm");
                    // No index file, list the directory if it is readable
                } else if (f.canRead()) {
                    String[] files = f.list();
                    String msg = "<html><body><h1>Directory " + uri
                            + "</h1><br/>";

                    if (uri.length() > 1) {
                        String u = uri.substring(0, uri.length() - 1);
                        int slash = u.lastIndexOf('/');
                        if (slash >= 0 && slash < u.length()) {
                            msg += "<b><a href=\""
                                    + uri.substring(0, slash + 1)
                                    + "\">..</a></b><br/>";
                        }
                    }

                    if (files != null) {
                        for (int i = 0; i < files.length; ++i) {
                            File curFile = new File(f, files[i]);
                            boolean dir = curFile.isDirectory();
                            if (dir) {
                                msg += "<b>";
                                files[i] += "/";
                            }

                            msg += "<a href=\"" + encodeUri(uri + files[i])
                                    + "\">" + files[i] + "</a>";

                            // Show file size
                            if (curFile.isFile()) {
                                long len = curFile.length();
                                msg += " &nbsp;<font size=2>(";
                                if (len < 1024) {
                                    msg += len + " bytes";
                                } else if (len < 1024 * 1024) {
                                    msg += len / 1024 + "."
                                            + (len % 1024 / 10 % 100) + " KB";
                                } else {
                                    msg += len / (1024 * 1024) + "." + len
                                            % (1024 * 1024) / 10 % 100 + " MB";
                                }
                                msg += ")</font>";
                            }
                            msg += "<br/>";
                            if (dir) {
                                msg += "</b>";
                            }
                        }
                    }
                    msg += "</body></html>";
                    res = new Response(msg);
                } else {
                    res = new Response(Response.Status.FORBIDDEN,
                            NanoHTTPD.MIME_PLAINTEXT,
                            "FORBIDDEN: No directory listing.");
                }
            }
        }
        try {
            if (res == null) {
                // Get MIME type from file name extension, if possible
                String mime = null;
                int dot = f.getCanonicalPath().lastIndexOf('.');
                if (dot >= 0) {
                    mime = MIME_TYPES.get(f.getCanonicalPath()
                            .substring(dot + 1).toLowerCase());
                }
                if (mime == null) {
                    mime = NanoHTTPD.MIME_HTML;
                }
                // Calculate etag
                String etag = Integer.toHexString((f.getAbsolutePath()
                        + f.lastModified() + "" + f.length()).hashCode());

                // Support (simple) skipping:
                long startFrom = 0;
                long endAt = -1;
                String range = header.get("range");
                if (range != null) {
                    if (range.startsWith("bytes=")) {
                        range = range.substring("bytes=".length());
                        int minus = range.indexOf('-');
                        try {
                            if (minus > 0) {
                                startFrom = Long.parseLong(range.substring(0,
                                        minus));
                                endAt = Long.parseLong(range
                                        .substring(minus + 1));
                            }
                        } catch (NumberFormatException ignored) {
                            BrewServer.LOG.info(ignored.getMessage());
                        }
                    }
                }

                // Change return code and add Content-Range header
                // when skipping is requested
                long fileLen = f.length();
                if (range != null && startFrom >= 0) {
                    if (startFrom >= fileLen) {
                        res = new Response(
                                Response.Status.RANGE_NOT_SATISFIABLE,
                                NanoHTTPD.MIME_PLAINTEXT, "");
                        res.addHeader("Content-Range", "bytes 0-0/" + fileLen);
                        res.addHeader("ETag", etag);
                    } else {
                        if (endAt < 0) {
                            endAt = fileLen - 1;
                        }
                        long newLen = endAt - startFrom + 1;
                        if (newLen < 0) {
                            newLen = 0;
                        }

                        final long dataLen = newLen;
                        FileInputStream fis = new FileInputStream(f) {
                            @Override
                            public int available() throws IOException {
                                return (int) dataLen;
                            }
                        };
                        fis.skip(startFrom);

                        res = new Response(Response.Status.PARTIAL_CONTENT,
                                mime, fis);
                        res.addHeader("Content-Length", "" + dataLen);
                        res.addHeader("Content-Range", "bytes " + startFrom
                                + "-" + endAt + "/" + fileLen);
                        res.addHeader("ETag", etag);
                    }
                } else {
                    if (etag.equals(header.get("if-none-match"))) {
                        res = new Response(Response.Status.NOT_MODIFIED, mime,
                                "");
                    } else {
                        res = new Response(Response.Status.OK, mime,
                                new FileInputStream(f));
                        res.addHeader("Content-Length", "" + fileLen);
                        res.addHeader("ETag", etag);
                    }
                }
            }
        } catch (IOException ioe) {
            res = new Response(Response.Status.FORBIDDEN,
                    NanoHTTPD.MIME_PLAINTEXT, "FORBIDDEN: Reading file failed.");
        }

        res.addHeader("Accept-Ranges", "bytes");
        // Announce that the file server accepts partial content requestes
        return res;
    }

    /**
     * URL-encodes everything between "/"-characters. Encodes spaces as '%20'
     * instead of '+'.
     * 
     * @param uri
     *            The URI to be encoded
     * @return The Encoded URI
     */
    private String encodeUri(final String uri) {
        String newUri = "";
        StringTokenizer st = new StringTokenizer(uri, "/ ", true);
        while (st.hasMoreTokens()) {
            String tok = st.nextToken();
            if (tok.equals("/")) {
                newUri += "/";
            } else if (tok.equals(" ")) {
                newUri += "%20";
            } else {
                try {
                    newUri += URLEncoder.encode(tok, "UTF-8");
                } catch (UnsupportedEncodingException ignored) {
                    BrewServer.LOG.info(ignored.getMessage());
                }
            }
        }
        return newUri;
    }

    /**
     * Add a new data point for the volume reading.
     * 
     * @param params
     *            List of parameters, must include "name" and "volume".
     *            Optional: probe details for onewire or direct ADC
     * @return A NanoHTTPD response
     */
    private Response addVolumePoint(Map<String, String> params) {
        JSONObject usage = new JSONObject();
        Map<String, String> parms = ParseParams(params);
        String inputUnit = parms.get("inputunit");
        usage.put("name", "Temperature probe name to add a volume point to");
        usage.put("volume",
                "Volume that is in the vessel to add a datapoint for");
        usage.put("units",
                "The Volume units to use (only required when setting up)");
        usage.put("onewire_address",
                "The one wire address to be used for analogue reads");
        usage.put("onewire_offset",
                "The one wire offset to be used for analogue reads");
        usage.put("adc_pin", "The ADC Pin to be used for analogue reads");

        System.out.println(params);
        String name = parms.get("name").trim();
        String volume = parms.get("volume").trim();
        String units = parms.get("units").trim();

        String onewire_add = parms.get("onewire_address").trim();
        String onewire_offset = parms.get("onewire_offset").trim();
        String adc_pin = parms.get("adc_pin").trim();

        String error_msg = "";

        if (name == null || volume == null) {
            error_msg = "No name or volume supplied";
            usage.put("Error", "Invalid parameters: " + error_msg);
            return new Response(Response.Status.BAD_REQUEST,
                    MIME_TYPES.get("json"), usage.toJSONString());
        }

        // We have a name and volume, lookup the temp probe
        Temp t = LaunchControl.findTemp(name);
        if (t == null) {
            error_msg = "Invalid temperature probe name supplied (" + name
                    + ")";
            usage.put("Error", "Invalid parameters: " + error_msg);
            return new Response(Response.Status.BAD_REQUEST,
                    MIME_TYPES.get("json"), usage.toJSONString());
        }
        // We have a temp probe, check to see if there's a valid volume input
        if (!t.hasVolume()) {
            if (onewire_add == null && onewire_offset == null
                    && adc_pin == null) {
                error_msg = "No volume input setup, and no valid volume inputs provided";
                usage.put("Error", "Invalid parameters: " + error_msg);
                return new Response(Response.Status.BAD_REQUEST,
                        MIME_TYPES.get("json"), usage.toJSONString());
            }

            // Check for ADC Pin
            if (adc_pin != null && !adc_pin.equals("")) {
                int adcpin = Integer.parseInt(adc_pin);
                if (0 < adcpin || adcpin > 7) {
                    error_msg = "Invalid ADC Pin offset";
                    usage.put("Error", "Invalid parameters: " + error_msg);
                    return new Response(Response.Status.BAD_REQUEST,
                            MIME_TYPES.get("json"), usage.toJSONString());
                }
                try {
                    if (!t.setupVolumes(adcpin, units)) {
                        error_msg = "Could not setup volumes for " + adcpin
                                + " Units: " + units;
                        usage.put("Error", "Invalid parameters: " + error_msg);
                        return new Response(Response.Status.BAD_REQUEST,
                                MIME_TYPES.get("json"), usage.toJSONString());
                    }
                } catch (InvalidGPIOException g) {
                    error_msg = "Invalid GPIO Pin " + g.getMessage();
                    usage.put("Error", "Invalid parameters: " + error_msg);
                    return new Response(Response.Status.BAD_REQUEST,
                            MIME_TYPES.get("json"), usage.toJSONString());
                }

            } else if (onewire_add != null && onewire_offset != null
                    && !onewire_add.equals("") && !onewire_offset.equals("")) {
                // Setup Onewire pin
                if (!t.setupVolumes(onewire_add, onewire_offset, units)) {
                    error_msg = "Could not setup volumes for " + onewire_add
                            + " offset: " + onewire_offset + " Units: " + units;
                    usage.put("Error", "Invalid parameters: " + error_msg);
                    return new Response(Response.Status.BAD_REQUEST,
                            MIME_TYPES.get("json"), usage.toJSONString());
                }
            }
        }

        // Should be good to go now
        try {
            BigDecimal actualVol = new BigDecimal(volume.replace(",", "."));
            if (t.addVolumeMeasurement(actualVol)) {
                return new Response(Response.Status.OK, MIME_TYPES.get("json"),
                        "{'Result': 'OK'}");
            }
        } catch (NumberFormatException nfe) {
            error_msg = "Could not setup volumes for " + volume + " Units: "
                    + units;
            LaunchControl.addMessage(error_msg);
            usage.put("Error", "Invalid parameters: " + error_msg);
            return new Response(Response.Status.BAD_REQUEST,
                    MIME_TYPES.get("json"), usage.toJSONString());
        }

        return new Response(Response.Status.BAD_REQUEST,
                MIME_TYPES.get("json"), usage.toJSONString());
    }

    /**
     * Get the graph data.
     * 
     * @param params
     *            A list of specific parameters
     * @return the JSON Response data
     */
    public NanoHTTPD.Response getGraphData(Map<String, String> params) {
        if (!LaunchControl.recorderEnabled()) {
            return new NanoHTTPD.Response("Recorder disabled");
        }

        Map<String, String> parms = ParseParams(params);
        // Have we been asked for a size?
        int size = 1000;

        if (parms.containsKey("size")) {
            try {
                size = Integer.parseInt((String) params.get("size"));
            } catch (NumberFormatException nfe) {
                size = 1000;
            }
        }

        String vessel = "";
        if (parms.containsKey("vessel")) {
            vessel = (String) parms.get("vessel");
        }

        // Read CSV files and make a JSON Response

        // Assume live for now until we can build a UI to deal with this
        boolean live = true;

        // If Live - read newest set
        String directory = null;
        if (live) {

            long newest = 0;
            File file = new File("graph-data/");
            if (file.isDirectory()) {
                File[] contents = file.listFiles();
                for (File content : contents) {
                    String name = content.getName();
                    try {
                        long current = Long.parseLong(name);
                        if (current > newest) {
                            newest = current;
                        }
                    } catch (NumberFormatException nfe) {
                        // Skip
                    }
                }
            }
            directory = String.valueOf(newest);
        }

        // Else Read based on time

        // Get files from directory
        File directoryFile = new File("graph-data/" + directory);

        File[] contents = directoryFile.listFiles();
        JSONObject xsData = new JSONObject();
        JSONObject axes = new JSONObject();
        JSONObject columns = new JSONObject();
        JSONArray dataBuffer = new JSONArray();
        long currentTime = System.currentTimeMillis();

        // Are we downloading the files?
        if (params.containsKey("download")
                && params.get("download").equalsIgnoreCase("true")) {
            String zipFileName = "graph-data/zipdownload-"
                + currentTime + ".zip";
            ZipFile zipFile = null;
            try {
                zipFile = new ZipFile(zipFileName);
            } catch (FileNotFoundException ioe) {
                BrewServer.LOG.warning(
                        "Couldn't create zip file at: " + zipFileName);
                BrewServer.LOG.warning(ioe.getLocalizedMessage());
            }

            for (File content : contents) {
                try {
                    if (content.getName().endsWith(".csv")
                            && content.getName().toLowerCase()
                                    .startsWith(vessel.toLowerCase())) {
                        zipFile.addToZipFile(content.getAbsolutePath());
                    }
                } catch (IOException ioe) {
                    BrewServer.LOG.warning(
                            "Couldn't add " + content.getAbsolutePath()
                            + " to zipfile");
                }
            }
            return serveFile(zipFileName, params, rootDir);
        }

        for (File content : contents) {
            if (content.getName().endsWith(".csv")
                    && content.getName().toLowerCase()
                            .startsWith(vessel.toLowerCase())) {
                String name = content.getName();

                // Strip off .csv
                name = name.substring(0, name.length() - 4);
                name = name.replace('-', ' ');

                if (parms.containsKey("bindto")
                        && ((String)parms.get("bindto"))
                        .endsWith("-graph_body")) {
                    name = name.substring(name.lastIndexOf(" ") + 1);
                }

                xsData.put(name, "x" + name);

                if (name.endsWith("duty")) {
                    axes.put(name, "y2");
                } else {
                    axes.put(name, "y");
                }

                JSONArray xArray = new JSONArray();
                JSONArray dataArray = new JSONArray();

                xArray.add("x" + name);
                dataArray.add(name);

                String lastLine = null;
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new FileReader(content));
                    String line;
                    String[] lArray = null;

                    while ((line = reader.readLine()) != null) {
                        // Each line contains the timestamp and the value
                        lArray = line.split(",");
                        xArray.add(BrewDay.sFormat
                                .format(new Date(Long.parseLong(lArray[0])))
                                );
                        dataArray.add(lArray[1].trim());
                    }

                    if (lArray != null && Long.parseLong(lArray[0]) != currentTime) {
                        xArray.add(BrewDay.sFormat
                                .format(new Date(currentTime)));
                        dataArray.add(lArray[1].trim());
                    }

                    dataBuffer.add(xArray);
                    dataBuffer.add(dataArray);
                } catch (Exception e) {
                    // Do nothing
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (Exception e) {
                            BrewServer.LOG.warning("Couldn't close file: "
                                    + content.getAbsolutePath());
                        }
                    }
                }

            }
        }

        JSONObject dataContent = new JSONObject();
        dataContent.put("columns", dataBuffer);
        if (parms.containsKey("updates")
                && Boolean.parseBoolean(parms.get("updates"))) {
            return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                    dataContent.toJSONString());
        }

        dataContent.put("xs", xsData);
        dataContent.put("axes", axes);
        dataContent.put("xFormat", "%H:%M:%S");

        JSONObject axisContent = new JSONObject();
        JSONObject y2Label = new JSONObject();
        y2Label.put("text", "Duty Cycle %");
        y2Label.put("position", "outer-middle");
        JSONObject y2 = new JSONObject();
        y2.put("show", "true");
        y2.put("label", y2Label);

        JSONObject y1Label = new JSONObject();
        y1Label.put("text", "Temperature");
        y1Label.put("position", "outer-middle");
        JSONObject y1 = new JSONObject();
        y1.put("show",  "true");
        y1.put("label", y1Label);
        
        JSONObject padding = new JSONObject();
        padding.put("top", 0);
        padding.put("bottom", 0);
        y1.put("padding", padding);

        JSONObject formatJSON = new JSONObject();
        formatJSON.put("format", "%H:%M:%S");
        JSONObject xContent = new JSONObject();
        xContent.put("type", "timeseries");
        xContent.put("tick", formatJSON);
        axisContent.put("x", xContent);
        axisContent.put("y", y1);
        axisContent.put("y2", y2);

        JSONObject finalJSON = new JSONObject();
        finalJSON.put("data", dataContent);
        finalJSON.put("axis", axisContent);

        if (parms.containsKey("bindto")) {
            finalJSON.put("bindto", "#" + parms.get("bindto"));
        } else {
            finalJSON.put("bindto", "#chart");
        }

        if (!((String) finalJSON.get("bindto")).endsWith("_body")) {
            JSONObject enabledJSON = new JSONObject();
            enabledJSON.put("enabled", true);
            finalJSON.put("zoom", enabledJSON);
        }

        return new NanoHTTPD.Response(Status.OK, MIME_TYPES.get("json"),
                finalJSON.toJSONString());

    }

    /**
     * Read the incoming parameters and update the name as appropriate.
     *
     * @param params
     *            The parameters from the client
     * @return True if success, false if failure
     */
    @SuppressWarnings("unchecked")
    private Response updateBreweryName(final Map<String, String> params) {

        JSONObject usage = new JSONObject();
        usage.put("Usage", "Set the brewery name");
        usage.put("name", "The new brewery name");

        Map<String, String> parms = ParseParams(params);
        String newName = parms.get("name");

        if (newName != null && newName != "" && newName.length() > 0) {
            LaunchControl.setName(newName);
        }

        return new Response(Response.Status.BAD_REQUEST,
                MIME_TYPES.get("json"), usage.toJSONString());

    }

    /**
     * Read the incoming parameters and update the name as appropriate.
     * @param files
     *            The parameters from the client
     * @return True if success, false if failure
     */
    @SuppressWarnings("unchecked")
    private Response updateBreweryImage(final Map<String, String> files) {

        JSONObject usage = new JSONObject();
        usage.put("Usage", "Set the brewery image");
        usage.put("files", "The new image file");

        if (files.size() == 1) {
            for (Map.Entry<String, String> entry : files.entrySet()) {

                try {
                    File uploadedFile = new File(entry.getValue());
                    String fileType = Files.probeContentType(
                            uploadedFile.toPath());

                    if (fileType.equalsIgnoreCase(MIME_TYPES.get("gif"))
                        || fileType.equalsIgnoreCase(MIME_TYPES.get("jpg"))
                        || fileType.equalsIgnoreCase(MIME_TYPES.get("jpeg"))
                        || fileType.equalsIgnoreCase(MIME_TYPES.get("png")))
                    {
                        File targetFile = new File(rootDir, "brewerImage.gif");
                        if (targetFile.exists()) {
                            targetFile.delete();
                        }
                        LaunchControl.copyFile(uploadedFile, targetFile);

                        targetFile.setReadable(true, false);
                        targetFile.setWritable(true, false);

                        LaunchControl.setFileOwner(targetFile);
                    }
                } catch (IOException e) {
                    usage.put("error", "Bad file");
                }
            }
        }
        return new Response(Response.Status.BAD_REQUEST,
                MIME_TYPES.get("json"), usage.toJSONString());

    }

    private Response updatePumpOrder(Map<String, String> parms) {
        Map<String, String> params = ParseParams(parms);
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Re-order the pumps");
        usage.put(":name=:position", "The new orders, starting at 0");

        Status status = Response.Status.BAD_REQUEST;

        for (Map.Entry<String, String> entry: params.entrySet()) {
            if (entry.getKey().equals("NanoHttpd.QUERY_STRING")) {
                continue;
            }
            Pump tPump = LaunchControl.findPump(entry.getKey());
            // Make Sure we're aware of this pump
            if (tPump == null) {
                LaunchControl.setMessage(
                        "Couldn't find Pump: " + entry.getKey());
                continue;
            }

            try {
                tPump.setPosition(Integer.parseInt(entry.getValue()));
            } catch (NumberFormatException nfe) {
                LaunchControl.setMessage(
                        "Couldn't parse " + entry.getValue() + " as an integer");
            }
            status = Response.Status.OK;
        }
        return new Response(status,
                MIME_TYPES.get("json"), usage.toJSONString());
    }

    /**
     * Delete a pump.
     * @param parms
     * @return
     */
    private Response deletePump(Map<String, String> parms) {
        Map<String, String> params = ParseParams(parms);
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Delete the specified pump");
        usage.put("name=:pump", "The pump to delete");
        Status status = Response.Status.OK;

        // find the pump
        String pumpName = params.get("name");
        if (pumpName != null) {
            LaunchControl.deletePump(pumpName);
        }
        return new Response(status, MIME_TYPES.get("json"),
                usage.toJSONString());
    }

    private Response updateTimerOrder(Map<String, String> parms) {

        Map<String, String> params = ParseParams(parms);
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Re-order the timers");
        usage.put(":name=:position", "The new orders, starting at 0");

        if (params.size() == 0) {
            return new Response(Response.Status.BAD_REQUEST,
                    MIME_TYPES.get("json"), usage.toJSONString());
        }

        for (Map.Entry<String, String> entry: params.entrySet()) {
            if (entry.getKey().equals("NanoHttpd.QUERY_STRING")) {
                continue;
            }

            Timer tTimer = LaunchControl.findTimer(entry.getKey());
            // Make Sure we're aware of this pump
            if (tTimer == null) {
                LaunchControl.setMessage(
                        "Couldn't find Timer: " + entry.getKey());
                continue;
            }

            try {
                tTimer.setPosition(Integer.parseInt(entry.getValue()));
            } catch (NumberFormatException nfe) {
                LaunchControl.setMessage(
                        "Couldn't parse " + entry.getValue() + " as an integer");
            }
        }
        return new Response(Response.Status.OK,
                MIME_TYPES.get("json"), usage.toJSONString());
    }

    /**
     * Delete a timer.
     * @param parms
     * @return
     */
    private Response deleteTimer(Map<String, String> parms) {
        Map<String, String> params = ParseParams(parms);
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Delete the specified timer");
        usage.put("name=:timer", "The timer to delete");
        Status status = Response.Status.OK;

        // find the pump
        String timerName = params.get("name");
        if (timerName != null) {
            LaunchControl.deleteTimer(timerName);
        }
        return new Response(status, MIME_TYPES.get("json"),
                usage.toJSONString());
    }

    private Response setScale(Map<String, String> parms) {
        Map<String, String> params = ParseParams(parms);
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Change the scale on all the temperature probes");
        usage.put("scale", "New scale 'F' or 'C'");

        Status status = Response.Status.OK;

        // Iterate all the temperature probes and change the scale
        if (!LaunchControl.setTempScales(params.get("scale"))) {
            status = Response.Status.BAD_REQUEST;
        }

        return new Response(status, MIME_TYPES.get("json"),
                usage.toJSONString());
    }
    
    private Response toggleDevice(Map<String, String> parms) {
        Map<String, String> params = ParseParams(parms);
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Hide a specific device from the UI");
        usage.put("device", "The device name to hide");

        Status status = Response.Status.OK;
        Temp temp = LaunchControl.findTemp(params.get("device"));

        if (temp != null) {
            temp.toggleVisibility();
        } else {
            status = Response.Status.BAD_REQUEST;
        }

        return new Response(status, MIME_TYPES.get("json"),
                usage.toJSONString());
    }

    private String updateSystemSettings(Map<String, String> parms) {
        Map<String, String> params = ParseParams(parms);
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Update the system settings");
        usage.put("recorder", "True/false to enable/disable the recorder.");
        usage.put("recorderDiff", "The tolerance to record data changes.");
        usage.put("recorderTime",
                "The time between sampling the data for recording.");

        if (params.containsKey("recorder")) {
            boolean recorderOn = params.get("recorder").equals("on");
            // If we're on, disable the recorder
            if (!recorderOn) {
                LaunchControl.disableRecorder();
            } else if (recorderOn) {
                LaunchControl.enableRecorder();
            }
        } else {
            LaunchControl.disableRecorder();
        }

        if (params.containsKey("recorderTolerence")) {
            try {
                StatusRecorder.THRESHOLD =
                    Double.parseDouble(params.get("recorderTolerence"));
            } catch (Exception e) {
                LaunchControl.setMessage(
                    "Failed to parse Recorder diff as a double\n" + e.getMessage()
                            + LaunchControl.getMessage());
            }
        }

        if (params.containsKey("recorderTime")) {
            try {
                StatusRecorder.SLEEP =
                    Long.parseLong(params.get("recorderTime"));
            } catch (Exception e) {
                LaunchControl.setMessage(
                    "Failed to parse Recorder time as a long\n" + e.getMessage()
                            + LaunchControl.getMessage());
            }
        }
        return usage.toJSONString();
    }

    /**
     * Set the gravity for the specified device.
     * @param parms
     * @return
     */
    public Response setGravity(Map<String, String> parms) {
        Map<String, String> params = ParseParams(parms);
        JSONObject usage = new JSONObject();
        usage.put("Usage", "Update the specified volume reader gravity");
        usage.put("device", "The device name to set the gravity on.");
        usage.put("gravity", "The new specific gravity to set");
        Status status = Status.OK;

        Temp temp = null;

        if (params.containsKey("inputunit")) {
            temp = LaunchControl.findTemp(params.get("inputunit"));
            if (temp == null) {
                LaunchControl.addMessage(
                        "Could not find the temperature probe for: "
                                + params.get("inputunit"));
                status = Status.BAD_REQUEST;
            }
        } else {
            LaunchControl.addMessage(
                    "No device provided when setting the gravity");
            status = Status.BAD_REQUEST;
        }

        if (params.containsKey("gravity")) {
            try {
                BigDecimal gravity = new BigDecimal(params.get("gravity"));
                if (temp != null) {
                    temp.setGravity(gravity);
                }
            } catch (NumberFormatException nfe) {
                LaunchControl.addMessage(
                        "Could not parse gravity as a decimal: "
                                + params.get("gravity"));
            }
        }

        return new Response(status, MIME_TYPES.get("json"),
                usage.toJSONString());
    }
}