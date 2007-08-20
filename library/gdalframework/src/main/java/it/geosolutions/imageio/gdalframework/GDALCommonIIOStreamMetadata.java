package it.geosolutions.imageio.gdalframework;

import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;

import org.w3c.dom.Node;

public class GDALCommonIIOStreamMetadata extends IIOMetadata {

	// package scope
	public static final String nativeMetadataFormatName = "org_gdal_imageio_common_stream_metadata";

	public static final String nativeMetadataFormatClassName = "it.geosolutions.imageio.gdalframework.GDALCommonIIOStreamMetadata";

	protected final String datasetNames[];

	public GDALCommonIIOStreamMetadata(String datasetNames[]) {
		super(false, nativeMetadataFormatName, nativeMetadataFormatClassName,
				null, null);
		this.datasetNames = datasetNames;
	}

	public Node getAsTree(String formatName) {
		if (!nativeMetadataFormatName.equalsIgnoreCase(formatName))
			throw new IllegalArgumentException(
					formatName
							+ " is not a recognized format name for gdal stream metadata.");
		final IIOMetadataNode root = new IIOMetadataNode(
				nativeMetadataFormatName);
		final IIOMetadataNode dataSetsNode = new IIOMetadataNode("DataSets");
		root.appendChild(dataSetsNode);
		// we need to take into account that when subdataset are supported we
		// have to remove 1 from the number of datasets we declare
		int length=datasetNames.length;
		if(length>1)
			length--;
		dataSetsNode.setAttribute("number", Integer.toString(length));
		for(int i=0;i<length;i++)
		{
			final IIOMetadataNode dataSetNode = new IIOMetadataNode("DataSet");
			dataSetNode.setAttribute("name", datasetNames[i]);
			dataSetsNode.appendChild(dataSetNode);
		}

		
		return root;
	}

	public boolean isReadOnly() {
		// TODO what to do with me?
		return false;
	}

	public void mergeTree(String formatName, Node root)
			throws IIOInvalidTreeException {
		// TODO what to do with me

	}

	public void reset() {
		// TODO what to do with me

	}

}