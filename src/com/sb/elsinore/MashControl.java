package com.sb.elsinore;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;

import org.joda.time.*;
import org.json.simple.JSONObject;



/********************
 * This class is for storing the mash steps and 
 * automatically updating the process as it goes.
 * 
 * @author doug
 *
 */

public class MashControl implements Runnable {
	
	public String outputControl = "";
	public String pumpControl = "";
	public boolean shutdownFlag = false;
	public double varianceF = 2.0;
	
	public HashMap<Integer, MashStep> mashStepList =  new HashMap<Integer, MashStep>();
	
	public void addMashStep() {
		addMashStep(mashStepList.size());
	}
	
	public MashStep addMashStep(Integer position) {
		mashStepList.put(position, new MashStep());
		return mashStepList.get(position);
	}
	
	public int getMashStepSize() {
		return mashStepList.size();
	}
	
	public MashStep getMashStep(Integer position) {
		Iterator<Entry<Integer, MashStep>> mashIt = mashStepList.entrySet().iterator();
		Entry<Integer, MashStep> e;
		
		while (mashIt.hasNext()) {
			e = mashIt.next();
			if (e.getKey() == position) {
				return e.getValue();
			}
		}
		return null;
	}
	
	public Entry<Integer, MashStep> getCurrentMashStep() {
		Iterator<Entry<Integer, MashStep>> mashIt = mashStepList.entrySet().iterator();
		Entry<Integer, MashStep> e;
		
		while (mashIt.hasNext()) {
			e = mashIt.next();
			if (e.getValue().isActive()) {
				return e;
			}
		}
		return null;
	}
	
	@Override
	public void run() {
		// Run through and update the times based on the currently active step
		Entry <Integer, MashStep> mashEntry = getCurrentMashStep();
		MashStep currentStep = mashEntry.getValue();
		Integer currentStepPosition = -1;
		if (currentStep == null) {
			currentStepPosition = mashEntry.getKey();
		}
		
		PID currentPID = LaunchControl.findPID(outputControl);
		
		while (true) {
			// Is there a step and an output control?
			if (currentStep != null && currentPID != null) {
				// Do stuff with the active step
				Date cDate = new Date();
				
				// Does the times need to be changed?
				double currentTempF = currentPID.getTempProbe().getTempF();
				// Give ourselves a 2F range, this can be changed in the future
				if ((currentTempF - varianceF) >= currentStep.getTargetTempAs("F")
						&& (currentTempF + varianceF) <= currentStep.getTargetTempAs("F")) {
					
					if (currentStep.getStart() == null) {
						// We've hit the target step temp, set the start date
						DateTime tDate = new DateTime();
						currentStep.setStart(tDate.toDate());
						
						// set the target End Date stamp
						tDate.plusMinutes(currentStep.getDuration());
						currentStep.setTargetEnd(tDate.toDate());
						
					} else if (cDate.compareTo(currentStep.getTargetEnd()) >= 0) {
						// Over or equal to the target end time, deactivate
						currentStep.deactivate(true);
						// Get the next step target time
						currentStepPosition += 1;
						currentStep = getMashStep(currentStepPosition);
						currentStep.activate();
						
						currentPID.setTemp(currentStep.getTargetTempAs(currentPID.getTempProbe().getScale()));
					}
				}
			
				// Have we gone past this mashStep Duration?
				
				// Does the target temp need to be updated
				if (currentPID.getTempF() != currentStep.getTargetTempAs("F")) {
					String tScale = currentPID.getTempProbe().getScale();
					currentPID.setTemp(currentStep.getTargetTempAs(tScale));
				}
			}
			
			try {
				// Sleep for 10 seconds
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// We got woken up.
				if (shutdownFlag) {
					return;
				}
			}
		}
	}
	
	private void setPIDTemp(PID p, MashStep m) {
	}
	
	public void activateStep(Integer position) {
		// deactivate all the steps first
		MashStep mashEntry = getMashStep(position);
		
		// Do we have a value
		if (mashEntry == null) {
			BrewServer.log.warning("Index out of bounds");
			return;
		}
		
		// Now we can reset the others
		for(Entry<Integer, MashStep> mEntry : mashStepList.entrySet()) {
			mEntry.getValue().deactivate(false);
		}
		
		mashEntry.activate();
	}
	
	public String getJSONDataString() {
		return getJSONData().toJSONString();
	}
	
	public JSONObject getJSONData() {
		JSONObject masterObject = new JSONObject();
		DateFormat lFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
		masterObject.put("pid", this.outputControl);
		
		for (Entry<Integer, MashStep> e : mashStepList.entrySet()) {
			MashStep step = e.getValue();
			JSONObject mashstep = new JSONObject();
			mashstep.put("target_temp", step.getTargetTemp());
			mashstep.put("target_temp_unit", step.getTempUnit());
			mashstep.put("duration", step.getDuration());
			mashstep.put("method", step.getMethod());
			mashstep.put("type", step.getType());
			
			try {
				mashstep.put("start_time", lFormat.format(step.getStart()));
				mashstep.put("target_time", lFormat.format(step.getTargetEnd()));
				mashstep.put("end_time", lFormat.format(step.getEnd()));
			} catch (NullPointerException npe) {
				// We know that some of these may be null, but don't care
			}
		
			masterObject.put(e.getKey(), mashstep);
		}
		
		return masterObject;
	}
	
}