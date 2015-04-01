package org.cloudbus.cloudsim;

import java.util.Iterator;
import java.util.List;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.MyCloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;

/**
 * My Datacenter extends Datacenter.java by overwriting some of its methods. The modifications are not important but
 * necessary for the new storage implementation.
 * 
 * @author Baptiste Louis
 * 
 */
public class MyDatacenter extends Datacenter {
	
	/**
	 * The constructor.
	 * 
	 * @param name
	 *            name
	 * @param characteristics
	 *            characteristics
	 * @param vmAllocationPolicy
	 *            VM allocation Policy
	 * @param storageList
	 *            list of Persistent Storage
	 * @param schedulingInterval
	 *            Scheduling Interval
	 * @throws Exception
	 */
	public MyDatacenter(
			String name,
			DatacenterCharacteristics characteristics,
			VmAllocationPolicy vmAllocationPolicy,
			List<? extends Storage> storageList,
			double schedulingInterval) throws Exception {
		super(
				name,
				characteristics,
				vmAllocationPolicy,
				storageList,
				schedulingInterval);
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.cloudbus.cloudsim.Datacenter#processEvent(org.cloudbus.cloudsim.core.SimEvent)
	 */
	@SuppressWarnings("javadoc")
	@Override
	public void processEvent(
			SimEvent ev) {
		
		// Handle my new Tag Event.
		if (ev.getTag() == MyCloudSimTags.CLOUDLET_FILES_DONE) {
			processCloudletFilesDone(ev);
		} else {
			super.processEvent(ev);
		}
	}
	
	/**
	 * Processes a "Cloudlet Files Done" Event.
	 * 
	 * @param ev
	 *            the SimEvent
	 */
	protected void processCloudletFilesDone(
			SimEvent ev) {
		
		// get data from event
		Object[] data = (Object[]) ev.getData();
		
		// retrieves data
		String action = (String) data[0];
		Cloudlet cl = (Cloudlet) data[1];
		File tempFile = (File) data[2];
		double tempTime = (double) data[4];
		Storage storage = (Storage) data[7];
		
		// print out confirmation
		Log.formatLine("%.3f: %s: Cloudlet # %d: <%s> %s on %s.",
				CloudSim.clock(),
				getName(),
				cl.getCloudletId(),
				tempFile.getName(),
				action,
				storage.getName());
		Log.formatLine("%10s Transaction time of %6.3f Seconde(s) according to %s specifications.",
				"",
				tempTime,
				storage.getName());
		Log.printLine();
	}
	
	/**
	 * Processes a Cloudlet submission.
	 * 
	 * @param ev
	 *            a SimEvent object
	 * @param ack
	 *            an acknowledgment
	 */
	@Override
	protected void processCloudletSubmit(
			SimEvent ev,
			boolean ack) {
		
		// The code is almost the same than in Datacenter.java but some modification has been done to avoid compilation
		// issues and
		
		updateCloudletProcessing();
		
		try {
			// gets the Cloudlet object
			Object obj = ev.getData();
			
			// test is the Object <obj> is a Cloudlet
			if (obj instanceof Cloudlet) {
				Cloudlet cl = (Cloudlet) obj;
				
				// checks whether this Cloudlet has finished or not
				if (cl.isFinished()) {
					String name = CloudSim.getEntityName(cl.getUserId());
					Log.printLine(getName() + ": Warning - Cloudlet #" + cl.getCloudletId() + " owned by " + name
							+ " is already completed/finished.");
					Log.printLine("Therefore, it is not being executed again");
					Log.printLine();
					
					// NOTE: If a Cloudlet has finished, then it won't be processed.
					// So, if ack is required, this method sends back a result.
					// If ack is not required, this method don't send back a result.
					// Hence, this might cause CloudSim to be hanged since waiting
					// for this Cloudlet back.
					if (ack) {
						int[] data = new int[3];
						data[0] = getId();
						data[1] = cl.getCloudletId();
						data[2] = CloudSimTags.FALSE;
						
						// unique tag = operation tag
						int tag = CloudSimTags.CLOUDLET_SUBMIT_ACK;
						sendNow(cl.getUserId(),
								tag,
								data);
					}
					
					sendNow(cl.getUserId(),
							CloudSimTags.CLOUDLET_RETURN,
							cl);
					
					return;
				}
				
				// process this Cloudlet to this CloudResource
				cl.setResourceParameter(getId(),
						getCharacteristics().getCostPerSecond(),
						getCharacteristics().getCostPerBw());
				
				int userId = cl.getUserId();
				int vmId = cl.getVmId();
				
				// Main modification compare to Datacenter.java
				// time to transfer the required files
				double fileTransferTime = predictRequiredFilesTransferTime(cl.getRequiredFiles());
				
				// time to transfer the data files (if the Cloudlet is MyCloudlet)
				if (cl instanceof MyCloudlet) {
					MyCloudlet mycl = (MyCloudlet) cl;
					fileTransferTime += predictDataFilesTransferTime(mycl.getDataFiles());
				}
				
				Host host = getVmAllocationPolicy().getHost(vmId,
						userId);
				Vm vm = host.getVm(vmId,
						userId);
				CloudletScheduler scheduler = vm.getCloudletScheduler();
				double estimatedFinishTime = scheduler.cloudletSubmit(cl,
						fileTransferTime);
				
				// if this cloudlet is in the execution queue
				if (estimatedFinishTime > 0.0 && !Double.isInfinite(estimatedFinishTime)) {
					estimatedFinishTime += fileTransferTime;
					send(getId(),
							estimatedFinishTime,
							CloudSimTags.VM_DATACENTER_EVENT);
				}
				
				if (ack) {
					int[] data = new int[3];
					data[0] = getId();
					data[1] = cl.getCloudletId();
					data[2] = CloudSimTags.TRUE;
					
					// unique tag = operation tag
					int tag = CloudSimTags.CLOUDLET_SUBMIT_ACK;
					sendNow(cl.getUserId(),
							tag,
							data);
				}
			}
		} catch (ClassCastException c) {
			Log.printLine(getName() + ".processCloudletSubmit(): " + "ClassCastException error.");
			c.printStackTrace();
		} catch (Exception e) {
			Log.printLine(getName() + ".processCloudletSubmit(): " + "Exception error.");
			e.printStackTrace();
		}
		
		checkCloudletCompletion();
	}
	
	/**
	 * Predict Required Files transfer time.
	 * 
	 * @param requiredFiles
	 *            the required files
	 * @return time the transfer time in Second(s)
	 */
	protected double predictRequiredFilesTransferTime(
			List<String> requiredFiles) {
		// initialization
		double time = 0.0;
		Iterator<String> iter = requiredFiles.iterator();
		
		// find each Files
		while (iter.hasNext()) {
			String fileName = iter.next();
			for (int i = 0; i < getStorageList().size(); i++) {
				Storage tempStorage = getStorageList().get(i);
				File tempFile = tempStorage.getFile(fileName);
				if (tempFile != null) {
					// increment the time
					time += tempFile.getSize() / tempStorage.getMaxTransferRate();
					break;
				}
			}
		}
		
		return time;
	}
	
	/**
	 * Predict data files transfer time.
	 * 
	 * @param dataFiles
	 *            the data files
	 * @return time the transfer time in Second(s)
	 */
	protected double predictDataFilesTransferTime(
			List<File> dataFiles) {
		// initialization
		double time = 0.0;
		Iterator<File> iter = dataFiles.iterator();
		
		// handle the case where there is no persistent storage
		if (getStorageList().size() == 0) {
			return time;
		}
		
		// handle each files
		while (iter.hasNext()) {
			File fileName = iter.next();
			// increment the time
			time += fileName.getSize() / getStorageList().get(0).getMaxTransferRate();
			
			// NOTE: For the prediction, it is assumed that the transfer rate of the target hard drive will be
			// approximately the same than the first hard drive in the storageList of this Datacenter.
		}
		
		return time;
	}
}
