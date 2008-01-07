/*
 *    JImageIO-extension - OpenSource Java Image translation Library
 *    http://www.geo-solutions.it/
 *	  https://imageio-ext.dev.java.net/
 *    (C) 2007, GeoSolutions
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package it.geosolutions.imageio.gdalframework;

import it.geosolutions.imageio.stream.output.FileImageOutputStreamExt;

import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferDouble;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.media.jai.PlanarImage;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.BandSelectDescriptor;

import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconstConstants;

/**
 * Main abstract class defining the main framework which needs to be used to
 * extend Image I/O architecture using <a href="http://www.gdal.org/"> GDAL
 * (Geospatial Data Abstraction Layer)</a> by means of SWIG (Simplified Wrapper
 * and Interface Generator) bindings in order to perform write operations.
 * 
 * @author Daniele Romagnoli, GeoSolutions.
 * @author Simone Giannecchini, GeoSolutions.
 */
public abstract class GDALImageWriter extends ImageWriter {

	private static final Logger LOGGER = Logger.getLogger(GDALImageWriter.class
			.toString());

	static {
		try {
			System.loadLibrary("gdaljni");
			gdal.AllRegister();
		} catch (UnsatisfiedLinkError e) {

			if (LOGGER.isLoggable(Level.INFO))
				LOGGER.info(new StringBuffer("Native library load failed.")
						.append(e.toString()).toString());
		}
	}

	/** Output File */
	protected File outputFile;

	/** Memory driver for creating {@link Dataset}s in memory. */
	protected final static Driver memDriver = gdal.GetDriverByName("MEM");

	/**
	 * Constructor for <code>GDALImageWriter</code>
	 */
	public GDALImageWriter(ImageWriterSpi originatingProvider) {
		super(originatingProvider);

	}

	public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
		throw new UnsupportedOperationException(
				"getDefaultStreamMetadata not implemented yet.");
	}

	public void write(IIOMetadata streamMetadata, IIOImage image,
			ImageWriteParam param) throws IOException {


		// /////////////////////////////////////////////////////////////////////
		//
		// Initial check on the capabilities of this writer and on the provided
		// parameters.
		//
		// /////////////////////////////////////////////////////////////////////
		final String driverName=(String) ((GDALImageWriterSpi) this.originatingProvider)
		.getSupportedFormats().get(0);
		final int writingCapabilities = GDALUtilities
				.formatWritingCapabilities(driverName);
		if (writingCapabilities == GDALUtilities.DriverCreateCapabilities.READ_ONLY)
			throw new IllegalStateException(
					"This writer seems to not support either create or create copy");
		if (image == null)
			throw new IllegalArgumentException(
					"The provided input image is invalid.");

		// Getting the source
		final PlanarImage inputRenderedImage = PlanarImage
				.wrapRenderedImage(image.getRenderedImage());
		final int sourceWidth = inputRenderedImage.getWidth();
		final int sourceHeight = inputRenderedImage.getHeight();

		int destinationWidth = -1;
		int destinationHeight = -1;
		int sourceRegionWidth = -1;
		int sourceRegionHeight = -1;
		int sourceRegionXOffset = -1;
		int sourceRegionYOffset = -1;
		int xSubsamplingFactor = -1;
		int ySubsamplingFactor = -1;

		if (param == null)
			param = getDefaultWriteParam();

		// //
		// get Rectangle object which will be used to clip the source image's
		// dimensions.
		// //
		Rectangle sourceRegion = param.getSourceRegion();
		if (sourceRegion != null) {
			sourceRegionWidth = (int) sourceRegion.getWidth();
			sourceRegionHeight = (int) sourceRegion.getHeight();
			sourceRegionXOffset = (int) sourceRegion.getX();
			sourceRegionYOffset = (int) sourceRegion.getY();

			//
			// correct for overextended source regions
			//
			if (sourceRegionXOffset + sourceRegionWidth > sourceWidth)
				destinationWidth = sourceWidth - sourceRegionXOffset;
			else
				destinationWidth = sourceRegionWidth;

			if (sourceRegionYOffset + sourceRegionHeight > sourceHeight)
				destinationHeight = sourceHeight - sourceRegionYOffset;
			else
				destinationHeight = sourceRegionHeight;
		} else {
			destinationWidth = sourceWidth;
			destinationHeight = sourceHeight;
			sourceRegionWidth = sourceWidth;
			sourceRegionHeight = sourceHeight;
			sourceRegionXOffset = sourceRegionYOffset = 0;
			sourceRegion = new Rectangle(sourceRegionWidth, sourceRegionHeight);
		}

		// get subsampling factors
		xSubsamplingFactor = param.getSourceXSubsampling();
		ySubsamplingFactor = param.getSourceYSubsampling();

		// get destination width and height
		destinationWidth = (destinationWidth - 1) / xSubsamplingFactor + 1;
		destinationHeight = (destinationHeight - 1) / ySubsamplingFactor + 1;

		final int dataType = GDALUtilities
				.retrieveGDALDataBufferType(inputRenderedImage.getSampleModel()
						.getDataType());
		final Vector myOptions = (Vector) ((GDALImageWriteParam) param)
				.getCreateOptionsHandler().getCreateOptions();

		
		// /////////////////////////////////////////////////////////////////////
		//
		// Some GDAL formats driver support both "Create" and "CreateCopy"
		// methods.
		// Some others simply support "CreateCopy" method which only allows to
		// create a new File from an existing Dataset.
		//
		// /////////////////////////////////////////////////////////////////////
		if (writingCapabilities == GDALUtilities.DriverCreateCapabilities.CREATE) {
			// Retrieving the number of bands
			final int nBands = inputRenderedImage.getNumBands();

			// Retrieving the file name.
			final String fileName = outputFile.getAbsolutePath();

			// Dataset creation
			final Driver driver=gdal.GetDriverByName(driverName);
			Dataset ds = driver.Create(fileName, destinationWidth,
					destinationHeight, nBands, dataType, myOptions);

			// Data Writing
			ds = writeData(ds, inputRenderedImage, sourceRegion, nBands,
					dataType, destinationWidth, destinationHeight, sourceWidth,
					sourceHeight);
			ds.FlushCache();
			GDALUtilities.closeDataSet(ds);
			return;

			// TODO: Adding additional writing operation (CRS,metadata,...)
		}

		// //
		// TODO: CHECK CRS & PROJECTIONS & ...
		// //

		// /////////////////////////////////////////////////////////////////
		//
		// Only create copy is supported
		//
		//
		// //
		// First of all, it is worth to point out that CreateCopy method
		// allows to create a File from an existing Dataset.
		// As first step, we need to retrieve the originary Dataset.
		// If the source image comes from a "pure" read operation, where
		// no parameters (like, as an instance, sourceRegion,
		// sourceSubSamplingX/Y) was defined, we can directly use the
		// Dataset which originated this image. The originating Dataset may
		// be found using the ImageReader. Otherwise, we need to build a
		// Dataset from the actual image using the memory driver.
		//
		// /////////////////////////////////////////////////////////////////
		Dataset sourceDataset = null;
		final Dataset writeDataset;
		final RenderedImage ri = image.getRenderedImage();

		// Getting the reader which read the coming image
		ImageReader reader = null;
		ImageReadParam readParam = null;
		final String[] properties = ri.getPropertyNames();
		if (properties != null) {
			// //
			//
			// In case the RenderedImage is coming from a JAI ImageRead
			// I can get the ImageReader as well as the ImageReadParam
			//
			// //
			final Object imageReader = ri.getProperty("JAI.ImageReader");
			if (imageReader != null && imageReader instanceof ImageReader)
				reader = (ImageReader) imageReader;

			// retrieving the ImageReadParam used by the read
			final Object imageReadParam = ri.getProperty("JAI.ImageReadParam");
			if (imageReadParam != null
					&& imageReadParam instanceof ImageReadParam)
				readParam = (ImageReadParam) imageReadParam;
		}
		boolean isAgdalImageReader = false;
		if (reader != null && reader instanceof GDALImageReader) {
			sourceDataset = ((GDALImageReader) reader)
					.getLastRecentlyUsedDataset();
			isAgdalImageReader = true;
		}

		// /////////////////////////////////////////////////////////////////
		//
		// Checking if the readParam contains parameters which changes
		// some image properties such size, position,... or if the reader
		// which originates the source image is not a GDALImageReader
		//
		// /////////////////////////////////////////////////////////////////
		final Driver driver=gdal.GetDriverByName(driverName);
		if ((readParam != null && isPreviousReadOperationParametrized(readParam))
				|| !isAgdalImageReader) {
			// create a Dataset from the originating image
			final File tempFile = File.createTempFile("datasetTemp", ".ds", null);
			tempFile.deleteOnExit();// TODO: Is needed?
			final Dataset tempDataset = createDatasetFromImage(ri, tempFile
					.getAbsolutePath());
			if (isAgdalImageReader && sourceDataset != null) {
				// Retrieving CRS and Projections from the original dataset
				final String projection = sourceDataset.GetProjection();
				final double geoTransform[] = new double[6];
				sourceDataset.GetGeoTransform(geoTransform);
				tempDataset.SetGeoTransform(geoTransform);
				tempDataset.SetProjection(projection);
			}
			writeDataset = driver.CreateCopy(outputFile.getPath(), tempDataset,
					0, myOptions);
			tempDataset.FlushCache();
			GDALUtilities.closeDataSet(tempDataset);
			tempFile.delete();
		} else {
			// Originating image come from a "pure" read operation.
			// I can use the Dataset coming from the reader as
			// sourceDataset.
			writeDataset = driver.CreateCopy(outputFile.getPath(),
					sourceDataset, 0, myOptions);
		}

		writeDataset.FlushCache();
		GDALUtilities.closeDataSet(writeDataset);
		

	}

	/**
	 * Given a previously created <code>Dataset</code>, containing no data,
	 * provides to store required data coming from an input
	 * <code>RenderedImage</code> in compliance with a set of parameter such
	 * as destination size, source size.
	 * 
	 * @param ds
	 *            the destination dataset
	 * @param inputRenderedImage
	 *            the input image containing data which need to be written
	 * @param sourceRegion
	 *            the rectangle used to clip the source image dimensions
	 * @param nBands
	 *            the number of bands need to be written
	 * @param dataType
	 *            the datatype
	 * @param destinationWidth
	 *            the width of the destination
	 * @param destinationHeight
	 *            the height of the destination
	 * @param sourceWidth
	 *            the widht of the original image
	 * @param sourceHeight
	 *            the height of the original image
	 * @return the <code>Dataset</code> resulting after the write operation
	 */
	private Dataset writeData(Dataset ds, RenderedImage inputRenderedImage,
			final Rectangle sourceRegion, final int nBands, final int dataType,
			final int destinationWidth, final int destinationHeight,
			final int sourceWidth, final int sourceHeight) {
		final int dataTypeSizeInBytes = gdal.GetDataTypeSize(dataType) / 8;
		final int pixels = destinationHeight * destinationWidth;
		int capacity = pixels * dataTypeSizeInBytes * nBands;

		// splitBands = false -> I read n Bands at once.
		// splitBands = true -> I need to read 1 Band at a time.
		boolean splitBands = false;

		if (capacity < 0) {
			// The number resulting from the product
			// "nBands*pixels*dataTypeSizeInBytes"
			// may be negative (A very high number which is not
			// "int representable")
			// In such a case, we will write 1 band at a time.
			capacity = pixels * dataTypeSizeInBytes;
			splitBands = true;
		}

		ByteBuffer[] bands = new ByteBuffer[nBands];
		DataBuffer db = null;

		if (dataType == gdalconstConstants.GDT_Byte) {
			if (!splitBands) {
				bands[0] = ByteBuffer.allocateDirect(capacity);
				for (int i = 0; i < nBands; i++) {
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					byte[] bytes = ((DataBufferByte) db).getData();
					bands[0].put(bytes, 0, bytes.length);
				}
			} else {
				for (int i = 0; i < nBands; i++) {
					bands[i] = ByteBuffer.allocateDirect(capacity);
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					byte[] bytes = ((DataBufferByte) db).getData();
					bands[i].put(bytes, 0, bytes.length);
				}
			}
		} else if (dataType == gdalconstConstants.GDT_UInt16) {
			if (!splitBands) {
				bands[0] = ByteBuffer.allocateDirect(capacity);
				bands[0].order(ByteOrder.nativeOrder());
				ShortBuffer buff = bands[0].asShortBuffer();
				for (int i = 0; i < nBands; i++) {
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					short[] shorts = ((DataBufferUShort) db).getData();
					buff.put(shorts, 0, shorts.length);
				}
			} else {
				for (int i = 0; i < nBands; i++) {
					bands[i] = ByteBuffer.allocateDirect(capacity);
					bands[i].order(ByteOrder.nativeOrder());
					ShortBuffer buff = bands[i].asShortBuffer();
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					short[] shorts = ((DataBufferUShort) db).getData();
					buff.put(shorts, 0, shorts.length);
				}
			}
		} else if (dataType == gdalconstConstants.GDT_Int16) {
			if (!splitBands) {
				bands[0] = ByteBuffer.allocateDirect(capacity);
				bands[0].order(ByteOrder.nativeOrder());
				ShortBuffer buff = bands[0].asShortBuffer();
				for (int i = 0; i < nBands; i++) {
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					short[] shorts = ((DataBufferShort) db).getData();
					buff.put(shorts, 0, shorts.length);
				}
			} else {
				for (int i = 0; i < nBands; i++) {
					bands[i] = ByteBuffer.allocateDirect(capacity);
					bands[i].order(ByteOrder.nativeOrder());
					ShortBuffer buff = bands[i].asShortBuffer();
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					short[] shorts = ((DataBufferShort) db).getData();
					buff.put(shorts, 0, shorts.length);
				}
			}
		} else if (dataType == gdalconstConstants.GDT_Int32) {
			if (!splitBands) {
				bands[0] = ByteBuffer.allocateDirect(capacity);
				bands[0].order(ByteOrder.nativeOrder());
				IntBuffer buff = bands[0].asIntBuffer();
				for (int i = 0; i < nBands; i++) {
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					int[] ints = ((DataBufferInt) db).getData();
					buff.put(ints, 0, ints.length);
				}
			} else {
				for (int i = 0; i < nBands; i++) {
					bands[i] = ByteBuffer.allocateDirect(capacity);
					bands[i].order(ByteOrder.nativeOrder());
					IntBuffer buff = bands[i].asIntBuffer();
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					int[] ints = ((DataBufferInt) db).getData();
					buff.put(ints, 0, ints.length);
				}
			}
		} else if (dataType == gdalconstConstants.GDT_Float32) {

			if (!splitBands) {
				bands[0] = ByteBuffer.allocateDirect(capacity);
				bands[0].order(ByteOrder.nativeOrder());
				FloatBuffer buff = bands[0].asFloatBuffer();
				for (int i = 0; i < nBands; i++) {
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					float[] floats = ((DataBufferFloat) db).getData();
					buff.put(floats, 0, floats.length);
				}
			} else {
				for (int i = 0; i < nBands; i++) {
					bands[i] = ByteBuffer.allocateDirect(capacity);
					bands[i].order(ByteOrder.nativeOrder());
					FloatBuffer buff = bands[i].asFloatBuffer();
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					float[] floats = ((DataBufferFloat) db).getData();
					buff.put(floats, 0, floats.length);
				}
			}

		} else if (dataType == gdalconstConstants.GDT_Float64) {

			if (!splitBands) {
				bands[0] = ByteBuffer.allocateDirect(capacity);
				bands[0].order(ByteOrder.nativeOrder());
				DoubleBuffer buff = bands[0].asDoubleBuffer();
				for (int i = 0; i < nBands; i++) {
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					double[] doubles = ((DataBufferDouble) db).getData();
					buff.put(doubles, 0, doubles.length);
				}
			} else {
				for (int i = 0; i < nBands; i++) {
					bands[i] = ByteBuffer.allocateDirect(capacity);
					bands[i].order(ByteOrder.nativeOrder());
					DoubleBuffer buff = bands[i].asDoubleBuffer();
					final RenderedOp bandi = BandSelectDescriptor.create(
							inputRenderedImage, new int[] { i }, null);
					db = bandi.getData(sourceRegion).getDataBuffer();
					double[] doubles = ((DataBufferDouble) db).getData();
					buff.put(doubles, 0, doubles.length);
				}
			}
		} else {
			// TODO: Throws exception
		}
		if (!splitBands)
			// I can perform a single Write operation.
			ds.WriteRaster_Direct(0, 0, destinationWidth, destinationHeight,
					sourceWidth, sourceHeight, dataType, nBands, bands[0]);
		else {
			// I need to perform a write operation for each band.
			for (int i = 0; i < nBands; i++)
				ds.GetRasterBand(i + 1).WriteRaster_Direct(0, 0,
						destinationWidth, destinationHeight, sourceWidth,
						sourceHeight, dataType, bands[i]);
		}
		return ds;
	}

	/**
	 * Given an input <code>ImageReadParam</code>, check if some properties
	 * (like, as an instance, subsampling factor, source region) are different
	 * than default values.
	 * 
	 * @param param
	 *            the <code>ImageReadParam</code> to be checked.
	 * @return <code>false</code> if checked properties of provided
	 *         imageReadParam have only default values.
	 * 
	 */
	private final boolean isPreviousReadOperationParametrized(
			ImageReadParam param) {
		// TODO: Add more parameter to be checked.
		if (param.getSourceXSubsampling() != 1
				|| param.getSourceYSubsampling() != 1
				|| param.getSubsamplingXOffset() != 0
				|| param.getSubsamplingYOffset() != 0
				|| param.getSourceRegion() != null)
			return true;
		return false;
	}

	/**
	 * Provides the ability to create a <code>Dataset</code> from an input
	 * <code>RenderedImage</code>. This is needed when we have to perform a
	 * GDAL <code>CreateCopy</code> which is based on an existing
	 * <code>Dataset</code> containing data to write.
	 * 
	 * @param inputRenderedImage
	 *            <code>RenderedImage</code> containing originating data.
	 * @param tempFile
	 *            A <code>String</code> representing the absolute path of the
	 *            <code>File</code> where to store the "In Memory" dataset.
	 * @return the created <code>dataset</code>
	 */
	private Dataset createDatasetFromImage(RenderedImage inputRenderedImage,
			String tempFile) {

		final SampleModel sm = inputRenderedImage.getSampleModel();
		final int nBands = sm.getNumBands();
		final int dataBufferType = sm.getDataType();
		final int eType = GDALUtilities
				.retrieveGDALDataBufferType(dataBufferType);

		// //
		//
		// Preparing to build a "In memory" raster dataset
		//
		// //

		final int height = inputRenderedImage.getHeight();
		final int width = inputRenderedImage.getWidth();
		final Rectangle sourceRegion = new Rectangle(width, height);
		final Dataset memDS = memDriver.Create(tempFile, width, height, nBands,
				eType, null);
		return writeData(memDS, inputRenderedImage, sourceRegion, nBands,
				eType, width, height, width, height);
	}

	public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType,
			ImageWriteParam param) {
		throw new UnsupportedOperationException(
				"getDefaultImageMetadata not implemented yet.");
	}

	public IIOMetadata convertStreamMetadata(IIOMetadata inData,
			ImageWriteParam param) {
		// throw new UnsupportedOperationException(
		// "convertStreamMetadata not implemented yet.");
		return null;
	}

	public IIOMetadata convertImageMetadata(IIOMetadata inData,
			ImageTypeSpecifier imageType, ImageWriteParam param) {
		// throw new UnsupportedOperationException(
		// "convertImageMetadata not implemented yet.");
		return null;
	}

	public void setOutput(Object output) {
		super.setOutput(output); // validates output
		if (output instanceof File)
			outputFile = (File) output;
		else if (output instanceof FileImageOutputStreamExt) {
			outputFile = ((FileImageOutputStreamExt) output).getFile();
			/**
			 * TODO: Uncomment this code to write ECW.
			 */
			// final FileImageOutputStreamExtImpl o =
			// (FileImageOutputStreamExtImpl) output;
			// try {
			// o.close();
			// } catch (IOException e) {
			//
			// }
			// assert outputFile.delete();
		} else if (output instanceof URL) {
			final URL tempURL = (URL) output;
			if (tempURL.getProtocol().equalsIgnoreCase("file")) {
				try {
					outputFile = new File(URLDecoder.decode(tempURL.getFile(),
							"UTF-8"));

				} catch (IOException e) {
					throw new RuntimeException("Not a Valid Input", e);
				}
			}
		}
	}

	public void write(IIOImage image) throws IOException {
		write(null, image, null);
	}

	public void write(RenderedImage image) throws IOException {
		write(null, new IIOImage(image, null, null), null);
	}
}
