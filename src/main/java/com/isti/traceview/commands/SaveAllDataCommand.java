package com.isti.traceview.commands;

import com.isti.traceview.AbstractCommand;
import com.isti.traceview.TraceView;
import com.isti.traceview.data.DataModule;
import com.isti.traceview.data.PlotDataProvider;
import org.apache.log4j.Logger;

/**
 * This command dumps all loaded data into temporary storage area
 * 
 * @author Max Kokoulin
 */
public class SaveAllDataCommand extends AbstractCommand {
	private static final Logger logger = Logger.getLogger(SaveAllDataCommand.class);

	public void run() {
		try {
			super.run();
			for (PlotDataProvider channel: TraceView.getDataModule().getAllChannels()) {
				channel.dump(DataModule.getTemporaryStorage().getSerialFileName(channel));
			}
		} catch (Exception e) {
			logger.error("Exception:", e);
		}
	}
}
