/*
 * {{{ header & license
 * Copyright (c) 2005 Wisconsin Court System
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * }}}
 */
package com.openhtmltopdf.layout;

import com.openhtmltopdf.css.constants.CSSName;
import com.openhtmltopdf.css.constants.IdentValue;
import com.openhtmltopdf.css.constants.MarginBoxName;
import com.openhtmltopdf.css.constants.PageElementPosition;
import com.openhtmltopdf.css.newmatch.PageInfo;
import com.openhtmltopdf.css.parser.CSSPrimitiveValue;
import com.openhtmltopdf.css.parser.PropertyValue;
import com.openhtmltopdf.css.style.CalculatedStyle;
import com.openhtmltopdf.css.style.CssContext;
import com.openhtmltopdf.css.style.EmptyStyle;
import com.openhtmltopdf.css.style.FSDerivedValue;
import com.openhtmltopdf.css.style.derived.ListValue;
import com.openhtmltopdf.css.style.derived.RectPropertySet;
import com.openhtmltopdf.newtable.CollapsedBorderValue;
import com.openhtmltopdf.newtable.TableBox;
import com.openhtmltopdf.newtable.TableCellBox;
import com.openhtmltopdf.render.*;
import com.openhtmltopdf.render.displaylist.TransformCreator;
import com.openhtmltopdf.util.LogMessageId;
import com.openhtmltopdf.util.XRLog;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;
import java.util.logging.Level;

/**
 * All positioned content as well as content with an overflow value other
 * than visible creates a layer.  Layers which define stacking contexts
 * provide the entry for rendering the box tree to an output device.  The main
 * purpose of this class is to provide an implementation of Appendix E of the
 * spec, but it also provides additional utility services including page
 * management and mapping boxes to coordinates (for e.g. links).  When
 * rendering to a paged output device, the layer is also responsible for laying
 * out absolute content (which is layed out after its containing block has
 * completed layout).
 */
public class Layer {
    public static final short PAGED_MODE_SCREEN = 1;
    public static final short PAGED_MODE_PRINT = 2;

    private Layer _parent;
    private boolean _stackingContext;
    private List<Layer> _children;
    private Box _master;

    private Box _end;

    private List<BlockBox> _floats;

    private boolean _fixedBackground;

    private boolean _inline;
    private boolean _requiresLayout;

    private List<PageBox> _pages;
    private PageBox _lastRequestedPage = null;

    private Set<BlockBox> _pageSequences;
    private List<BlockBox> _sortedPageSequences;

    private Map<String, List<BlockBox>> _runningBlocks;

    private Box _selectionStart;
    private Box _selectionEnd;

    private int _selectionStartX;
    private int _selectionStartY;

    private int _selectionEndX;
    private int _selectionEndY;
    
    private boolean _forDeletion;
    private boolean _hasFixedAncester;
    
    /**
     * @see {@link #getCurrentTransformMatrix()}
     */
    private AffineTransform _ctm;
    private final boolean _hasLocalTransform;

    /**
     * Creates the root layer.
     */
    public Layer(Box master, CssContext c) {
        this(null, master, c);
        setStackingContext(true);
    }

    /**
     * Creates a child layer.
     */
    public Layer(Layer parent, Box master, CssContext c) {
        _parent = parent;
        _master = master;
        setStackingContext(
                (master.getStyle().isPositioned() && !master.getStyle().isAutoZIndex()) ||
                (!master.getStyle().isIdent(CSSName.TRANSFORM, IdentValue.NONE)));
        master.setLayer(this);
        master.setContainingLayer(this);
        _hasLocalTransform = !master.getStyle().isIdent(CSSName.TRANSFORM, IdentValue.NONE);
        _hasFixedAncester = (parent != null && parent._hasFixedAncester) || master.getStyle().isFixed();
    }
    
    /** 
     * Recursively propagates the transformation matrix. This must be done after layout of the master
     * box and its children as this method relies on the box width and height for relative units in the 
     * transforms and transform origins.
     */
    public void propagateCurrentTransformationMatrix(CssContext c) {
    	AffineTransform parentCtm = _parent == null ? null : _parent._ctm;
    	_ctm = _hasLocalTransform ?
        		TransformCreator.createDocumentCoordinatesTransform(getMaster(), c, parentCtm) : parentCtm;
        		
        for (Layer child : getChildren()) {
        	child.propagateCurrentTransformationMatrix(c);
        }
    }
    
    /**
     * The document coordinates current transform, this is cumulative from layer to child layer.
     * May be null, if identity transform is in effect.
     * Used to check if a box belonging to this layer sits on a particular page after the
     * transform is applied.
     * This method can only be used after {@link #propagateCurrentTransformationMatrix(CssContext)} has been
     * called on the root layer.
     * @return null or affine transform.
     */
    public AffineTransform getCurrentTransformMatrix() {
    	return _ctm;
    }
    
    public boolean hasLocalTransform() {
    	return _hasLocalTransform;
    }
    
    public void setForDeletion(boolean forDeletion) {
        this._forDeletion = forDeletion;
    }
    
    public boolean isForDeletion() {
        return this._forDeletion;
    }
    
    public boolean hasFixedAncester() {
        return _hasFixedAncester;
    }
    
    public Layer getParent() {
        return _parent;
    }
    
    public boolean isStackingContext() {
        return _stackingContext;
    }

    public void setStackingContext(boolean stackingContext) {
        _stackingContext = stackingContext;
    }

    public int getZIndex() {
    	if (_master.getStyle().isIdent(CSSName.Z_INDEX, IdentValue.AUTO)) {
    		return 0;
    	}
        return (int) _master.getStyle().asFloat(CSSName.Z_INDEX);
    }

    public boolean isZIndexAuto() {
    	return _master.getStyle().isIdent(CSSName.Z_INDEX, IdentValue.AUTO);
    }

    public Box getMaster() {
        return _master;
    }

    public void addChild(Layer layer) {
        if (_children == null) {
            _children = new ArrayList<>();
        }
        _children.add(layer);
    }

    public static PageBox createPageBox(CssContext c, String pseudoPage) {
        PageBox result = new PageBox();

        String pageName = null;
        // HACK We only create pages during layout, but the OutputDevice
        // queries page positions and since pages are created lazily, changing
        // this method to use LayoutContext is tricky
        if (c instanceof LayoutContext) {
            pageName = ((LayoutContext)c).getPageName();
        }

        PageInfo pageInfo = c.getCss().getPageStyle(pageName, pseudoPage);
        result.setPageInfo(pageInfo);

        CalculatedStyle cs = new EmptyStyle().deriveStyle(pageInfo.getPageStyle());
        result.setStyle(cs);
        result.setOuterPageWidth(result.getWidth(c));

        return result;
    }

    /**
     * FIXME: Only used when we reset a box, so trying to remove at sometime in the future.
     */
    public void removeFloat(BlockBox floater) {
        if (_floats != null) {
            _floats.remove(floater);
        }
    }

    @Deprecated  // We are moving painting out of Layer to either DisplayListPainter or SimplePainter.
    private void paintFloats(RenderingContext c) {
        if (_floats != null) {
            for (int i = _floats.size() - 1; i >= 0; i--) {
                BlockBox floater = _floats.get(i);
                paintAsLayer(c, floater);
            }
        }
    }

    @Deprecated
    private void paintLayers(RenderingContext c, List<Layer> layers) {
        for (Layer layer : layers) {
            layer.paint(c);
        }
    }

    public static final int POSITIVE = 1;
    public static final int ZERO = 2;
    public static final int NEGATIVE = 3;
    public static final int AUTO = 4;

    public void addFloat(BlockBox floater, BlockFormattingContext bfc) {
        if (_floats == null) {
            _floats = new ArrayList<>();
        }

        _floats.add(floater);

        floater.getFloatedBoxData().setDrawingLayer(this);
    }

    /**
     * Called recusively to collect all descendant layers in a stacking context so they can be painted in correct order.
     * Those descendants that are under their own stacking contexts are excluded.
     * @param which NEGATIVE ZERO POSITIVE AUTO corresponding to z-index property.
     * @return
     */
    public List<Layer> collectLayers(int which) {
        List<Layer> result = new ArrayList<>();
        List<Layer> children = getChildren();

        result.addAll(getStackingContextLayers(which));

        for (Layer child : children) {
            if (! child.isStackingContext()) {
                if (child.isForDeletion()) {
                    // Do nothing...
                } else if (which == AUTO && child.isZIndexAuto()) {
            		result.add(child);
            	} else if (which == NEGATIVE && child.getZIndex() < 0) {
            		result.add(child);
            	} else if (which == POSITIVE && child.getZIndex() > 0) {
            		result.add(child);
            	} else if (which == ZERO && !child.isZIndexAuto() && child.getZIndex() == 0) {
            		result.add(child);
            	}
                result.addAll(child.collectLayers(which));
            }
        }

        return result;
    }

    private List<Layer> getStackingContextLayers(int which) {
        List<Layer> result = new ArrayList<>();
        List<Layer> children = getChildren();

        for (Layer target : children) {
            if (target.isForDeletion()) {
                // Do nothing...
            } else if (target.isStackingContext()) {
            	if (!target.isZIndexAuto()) {
                    int zIndex = target.getZIndex();
                    if (which == NEGATIVE && zIndex < 0) {
                        result.add(target);
                    } else if (which == POSITIVE && zIndex > 0) {
                        result.add(target);
                    } else if (which == ZERO && zIndex == 0) {
                        result.add(target);
                    }
            	} else if (which == AUTO) {
            		result.add(target);
            	}
            }
        }

        return result;
    }

    public List<Layer> getSortedLayers(int which) {
        List<Layer> result = collectLayers(which);

        Collections.sort(result, (l1, l2) -> l1.getZIndex() - l2.getZIndex());

        return result;
    }

    @Deprecated
    private void paintBackgroundsAndBorders(
            RenderingContext c, List<Box> blocks,
            Map<TableCellBox, List<CollapsedBorderSide>> collapsedTableBorders, BoxRangeLists rangeLists) {
        BoxRangeHelper helper = new BoxRangeHelper(c.getOutputDevice(), rangeLists.getBlock());

        for (int i = 0; i < blocks.size(); i++) {
            helper.popClipRegions(c, i);

            BlockBox box = (BlockBox)blocks.get(i);

            box.paintBackground(c);
            box.paintBorder(c);
            if (c.debugDrawBoxes()) {
                box.paintDebugOutline(c);
            }

            if (collapsedTableBorders != null && box instanceof TableCellBox) {
                TableCellBox cell = (TableCellBox)box;
                if (cell.hasCollapsedPaintingBorder()) {
                    List<CollapsedBorderSide> borders = collapsedTableBorders.get(cell);
                    if (borders != null) {
                        paintCollapsedTableBorders(c, borders);
                    }
                }
            }

            helper.pushClipRegion(c, i);
        }

        helper.popClipRegions(c, blocks.size());
    }

    @Deprecated // We no longer support interactive or selection.
    private void paintSelection(RenderingContext c, List<Box> lines) {
        if (c.getOutputDevice().isSupportsSelection()) {
            for (Iterator<Box> i = lines.iterator(); i.hasNext();) {
                Box box = i.next();
                if (box instanceof InlineLayoutBox) {
                    ((InlineLayoutBox)box).paintSelection(c);
                }
            }
        }
    }

    public Dimension getPaintingDimension(LayoutContext c) {
        return calcPaintingDimension(c).getOuterMarginCorner();
    }

    @Deprecated
    private void paintInlineContent(RenderingContext c, List<Box> lines, BoxRangeLists rangeLists) {
        BoxRangeHelper helper = new BoxRangeHelper(
                c.getOutputDevice(), rangeLists.getInline());

        for (int i = 0; i < lines.size(); i++) {
            helper.popClipRegions(c, i);
            helper.pushClipRegion(c, i);

            if (lines.get(i) instanceof  InlinePaintable) {
                ((InlinePaintable) lines.get(i)).paintInline(c);
            }
        }

        helper.popClipRegions(c, lines.size());
    }

    @Deprecated
    public void paint(RenderingContext c) {
        if (getMaster().getStyle().isFixed()) {
            positionFixedLayer(c);
        }

        List<AffineTransform> inverse = null;

        if (isRootLayer()) {
            getMaster().paintRootElementBackground(c);
        }

        if (! isInline() && ((BlockBox)getMaster()).isReplaced()) {
            inverse = applyTranform(c, getMaster());
            paintLayerBackgroundAndBorder(c);
            paintReplacedElement(c, (BlockBox)getMaster());
            c.getOutputDevice().popTransforms(inverse);
        } else {
            BoxRangeLists rangeLists = new BoxRangeLists();

            List<Box> blocks = new ArrayList<>();
            List<Box> lines = new ArrayList<>();

            BoxCollector collector = new BoxCollector();
            collector.collect(c, c.getOutputDevice().getClip(), this, blocks, lines, rangeLists);

            inverse = applyTranform(c, getMaster());

            if (! isInline()) {
                paintLayerBackgroundAndBorder(c);
                if (c.debugDrawBoxes()) {
                    ((BlockBox)getMaster()).paintDebugOutline(c);
                }
            }

            if (isRootLayer() || isStackingContext()) {
                paintLayers(c, getSortedLayers(NEGATIVE));
            }

            Map<TableCellBox, List<CollapsedBorderSide>> collapsedTableBorders = collectCollapsedTableBorders(c, blocks);
            paintBackgroundsAndBorders(c, blocks, collapsedTableBorders, rangeLists);
            paintFloats(c);
            paintListMarkers(c, blocks, rangeLists);
            paintInlineContent(c, lines, rangeLists);
            paintReplacedElements(c, blocks, rangeLists);
            paintSelection(c, lines); // XXX do only when there is a selection

            if (isRootLayer() || isStackingContext()) {
                paintLayers(c, collectLayers(AUTO));
                // TODO z-index: 0 layers should be painted atomically
                paintLayers(c, getSortedLayers(ZERO));
                paintLayers(c, getSortedLayers(POSITIVE));
            }

            c.getOutputDevice().popTransforms(inverse);
        }

    }

    @Deprecated // Moving to TransformCreator
    private float convertAngleToRadians(PropertyValue param) {
    	if (param.getPrimitiveType() == CSSPrimitiveValue.CSS_DEG) {
    		return (float) Math.toRadians(param.getFloatValue());
    	} else if (param.getPrimitiveType() == CSSPrimitiveValue.CSS_RAD) {
    		return param.getFloatValue();
    	} else { // if (param.getPrimitiveType() == CSSPrimitiveValue.CSS_GRAD)
    		return (float) (param.getFloatValue() * (Math.PI / 200));
    	}
    }

    public List<BlockBox> getFloats() {
        return _floats == null ? Collections.emptyList() : _floats;
    }

    /**
     * Applies the transforms specified for the box and returns a list of inverse transforms that should be
     * applied once the transformed element has been output.
     */
    @Deprecated
	protected List<AffineTransform> applyTranform(RenderingContext c, Box box) {
		FSDerivedValue transforms = box.getStyle().valueByName(CSSName.TRANSFORM);
		if (transforms.isIdent() && transforms.asIdentValue() == IdentValue.NONE)
			return Collections.emptyList();

		// By default the transform point is the lower left of the page, so we need to
		// translate to correctly apply transform.
		float relOriginX = box.getStyle().getFloatPropertyProportionalWidth(CSSName.FS_TRANSFORM_ORIGIN_X,
				box.getWidth(), c);
		float relOriginY = box.getStyle().getFloatPropertyProportionalHeight(CSSName.FS_TRANSFORM_ORIGIN_Y,
				box.getHeight(), c);

		float flipFactor = c.getOutputDevice().isPDF() ? -1 : 1;

		float absTranslateX = relOriginX + box.getAbsX();
		float absTranslateY = relOriginY + box.getAbsY();

		float relTranslateX = absTranslateX - c.getOutputDevice().getAbsoluteTransformOriginX();
		float relTranslateY = absTranslateY - c.getOutputDevice().getAbsoluteTransformOriginY();
		/*
		 * We must handle the page margin in the PDF case.
		 */
		if (c.getOutputDevice().isPDF()) {
			RectPropertySet margin = c.getPage().getMargin(c);
			relTranslateX += margin.left();
			relTranslateY += margin.top();
			
			/*
			 * We must apply the top/bottom margins from the previous pages, otherwise 
			 * our transform center is wrong.
			 */
			for (int i = 0; i < c.getPageNo() && i < getPages().size(); i++) {
				RectPropertySet prevMargin = getPages().get(i).getMargin(c);
				relTranslateY += prevMargin.top() + prevMargin.bottom();
			}
			

			MarginBoxName[] marginBoxNames = c.getPage().getCurrentMarginBoxNames();
			if (marginBoxNames != null) {
				boolean isLeft = false, isTop = false, isRight = false, isTopRight = false, isTopLeft = true,
						isBottom = false, isBottomRight = false, isBottomLeft = false;
				for (MarginBoxName name : marginBoxNames) {
					if (name == MarginBoxName.LEFT_TOP || name == MarginBoxName.LEFT_MIDDLE
							|| name == MarginBoxName.LEFT_BOTTOM)
						isLeft = true;
					if (name == MarginBoxName.TOP_LEFT || name == MarginBoxName.TOP_CENTER
							|| name == MarginBoxName.TOP_RIGHT)
						isTop = true;
					if (name == MarginBoxName.BOTTOM_LEFT || name == MarginBoxName.BOTTOM_CENTER
							|| name == MarginBoxName.BOTTOM_RIGHT)
						isBottom = true;
					if (name == MarginBoxName.TOP_LEFT_CORNER)
						isTopLeft = true;
					if (name == MarginBoxName.TOP_RIGHT_CORNER)
						isTopRight = true;
					if (name == MarginBoxName.BOTTOM_LEFT_CORNER)
						isBottomLeft = true;
					if (name == MarginBoxName.BOTTOM_RIGHT_CORNER)
						isBottomRight = true;

				}
				if (isLeft)
					relTranslateX -= margin.left();
				if (isTop )
					relTranslateY -= margin.top();
				if( isBottom )
					relTranslateY -= margin.top()+ margin.bottom();
				if (isTopLeft) {
					relTranslateX -= margin.left();
					relTranslateY -= margin.top();
				}
				if (isTopRight) {
					relTranslateX -= margin.left();
					relTranslateY -= margin.top();
				}
				if (isRight) {
					relTranslateY -= margin.top();
					relTranslateX -= margin.left() + margin.right();
				}
				if (isBottom) {
					//relTranslateX -= margin.left();
					relTranslateY -= margin.top() + margin.bottom();
				}
				if (isBottomLeft) {
					//relTranslateX -= margin.left();
					//relTranslateY -= margin.top() + margin.bottom();
				}
				if (isBottomRight) {
					relTranslateX -= margin.left();
					relTranslateY -= margin.top() + margin.bottom();
				}
			}
		}

		List<PropertyValue> transformList = (List<PropertyValue>) ((ListValue) transforms).getValues();
		List<AffineTransform> resultTransforms = new ArrayList<>();
		AffineTransform translateToOrigin = AffineTransform.getTranslateInstance(relTranslateX, relTranslateY);
		AffineTransform translateBackFromOrigin = AffineTransform.getTranslateInstance(-relTranslateX, -relTranslateY);

		resultTransforms.add(translateToOrigin);

		applyTransformFunctions(flipFactor, transformList, resultTransforms);

		resultTransforms.add(translateBackFromOrigin);

		return c.getOutputDevice().pushTransforms(resultTransforms);
	}

    @Deprecated
	private void applyTransformFunctions(float flipFactor, List<PropertyValue> transformList, List<AffineTransform> resultTransforms) {
		for (PropertyValue transform : transformList) {
			String fName = transform.getFunction().getName();
			List<PropertyValue> params = transform.getFunction().getParameters();

			if ("rotate".equalsIgnoreCase(fName)) {
				float radians = flipFactor * this.convertAngleToRadians(params.get(0));
				resultTransforms.add(AffineTransform.getRotateInstance(radians));
			} else if ("scale".equalsIgnoreCase(fName) || "scalex".equalsIgnoreCase(fName)
					|| "scaley".equalsIgnoreCase(fName)) {
				float scaleX = params.get(0).getFloatValue();
				float scaleY = params.get(0).getFloatValue();
				if (params.size() > 1)
					scaleY = params.get(1).getFloatValue();
				if ("scalex".equalsIgnoreCase(fName))
					scaleY = 1;
				if ("scaley".equalsIgnoreCase(fName))
					scaleX = 1;
				resultTransforms.add(AffineTransform.getScaleInstance(scaleX, scaleY));
			} else if ("skew".equalsIgnoreCase(fName)) {
				float radiansX = flipFactor * this.convertAngleToRadians(params.get(0));
				float radiansY = 0;
				if (params.size() > 1)
					radiansY = this.convertAngleToRadians(params.get(1));
				resultTransforms.add(AffineTransform.getShearInstance(Math.tan(radiansX), Math.tan(radiansY)));
			} else if ("skewx".equalsIgnoreCase(fName)) {
				float radians = flipFactor * this.convertAngleToRadians(params.get(0));
				resultTransforms.add(AffineTransform.getShearInstance(Math.tan(radians), 0));
			} else if ("skewy".equalsIgnoreCase(fName)) {
				float radians = flipFactor * this.convertAngleToRadians(params.get(0));
				resultTransforms.add(AffineTransform.getShearInstance(0, Math.tan(radians)));
			} else if ("matrix".equalsIgnoreCase(fName)) {
				resultTransforms.add(new AffineTransform(params.get(0).getFloatValue(), params.get(1).getFloatValue(),
								params.get(2).getFloatValue(), params.get(3).getFloatValue(),
								params.get(4).getFloatValue(), params.get(5).getFloatValue()));
			} else if ("translate".equalsIgnoreCase(fName)) {
			    XRLog.log(Level.WARNING, LogMessageId.LogMessageId1Param.LAYOUT_FUNCTION_NOT_IMPLEMENTED, "translate");
			} else if ("translateX".equalsIgnoreCase(fName)) {
                XRLog.log(Level.WARNING, LogMessageId.LogMessageId1Param.LAYOUT_FUNCTION_NOT_IMPLEMENTED, "translateX");
			} else if ("translateY".equalsIgnoreCase(fName)) {
                XRLog.log(Level.WARNING, LogMessageId.LogMessageId1Param.LAYOUT_FUNCTION_NOT_IMPLEMENTED, "translateY");
			}
		}
	}

    @Deprecated // Currently not using the find functionality and considering removing.
	private Box find(CssContext cssCtx, int absX, int absY, List<Layer> layers, boolean findAnonymous) {
        Box result = null;
        // Work backwards since layers are painted forwards and we're looking
        // for the top-most box
        for (int i = layers.size()-1; i >= 0; i--) {
            Layer l = layers.get(i);
            result = l.find(cssCtx, absX, absY, findAnonymous);
            if (result != null) {
                return result;
            }
        }
        return result;
    }

    @Deprecated
    public Box find(CssContext cssCtx, int absX, int absY, boolean findAnonymous) {
        Box result = null;
        if (isRootLayer() || isStackingContext()) {
            result = find(cssCtx, absX, absY, getSortedLayers(POSITIVE), findAnonymous);
            if (result != null) {
                return result;
            }

            result = find(cssCtx, absX, absY, getSortedLayers(ZERO), findAnonymous);
            if (result != null) {
                return result;
            }

            result = find(cssCtx, absX, absY, collectLayers(AUTO), findAnonymous);
            if (result != null) {
                return result;
            }
        }

        for (int i = 0; i < getFloats().size(); i++) {
            Box floater = getFloats().get(i);
            result = floater.find(cssCtx, absX, absY, findAnonymous);
            if (result != null) {
                return result;
            }
        }

        result = getMaster().find(cssCtx, absX, absY, findAnonymous);
        if (result != null) {
            return result;
        }

        if (isRootLayer() || isStackingContext()) {
            result = find(cssCtx, absX, absY, getSortedLayers(NEGATIVE), findAnonymous);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    @Deprecated
    private void paintCollapsedTableBorders(RenderingContext c, List<CollapsedBorderSide> borders) {
        for (Iterator<CollapsedBorderSide> i = borders.iterator(); i.hasNext(); ) {
            CollapsedBorderSide border = i.next();
            border.getCell().paintCollapsedBorder(c, border.getSide());
        }
    }

    // Bit of a kludge here.  We need to paint collapsed table borders according
    // to priority so (for example) wider borders float to the top and aren't
    // overpainted by thinner borders.  This method scans the block boxes
    // we're about to draw and returns a map with the last cell in a given table
    // we'll paint as a key and a sorted list of borders as values.  These are
    // then painted after we've drawn the background for this cell.
    @Deprecated
    private Map<TableCellBox, List<CollapsedBorderSide>> collectCollapsedTableBorders(RenderingContext c, List<Box> blocks) {
        Map<TableBox, List<CollapsedBorderSide>> cellBordersByTable = new HashMap<>();
        Map<TableBox, TableCellBox> triggerCellsByTable = new HashMap<>();

        Set<CollapsedBorderValue> all = new HashSet<>();
        for (Iterator<Box> i = blocks.iterator(); i.hasNext(); ) {
            Box b = i.next();
            if (b instanceof TableCellBox) {
                TableCellBox cell = (TableCellBox)b;
                if (cell.hasCollapsedPaintingBorder()) {
                    List<CollapsedBorderSide> borders = cellBordersByTable.get(cell.getTable());
                    if (borders == null) {
                        borders = new ArrayList<>();
                        cellBordersByTable.put(cell.getTable(), borders);
                    }
                    triggerCellsByTable.put(cell.getTable(), cell);
                    cell.addCollapsedBorders(all, borders);
                }
            }
        }

        if (triggerCellsByTable.size() == 0) {
            return null;
        } else {
            Map<TableCellBox, List<CollapsedBorderSide>> result = new HashMap<>();

            for (Iterator<TableCellBox> i = triggerCellsByTable.values().iterator(); i.hasNext(); ) {
                TableCellBox cell = i.next();
                List<CollapsedBorderSide> borders = cellBordersByTable.get(cell.getTable());
                Collections.sort(borders);
                result.put(cell, borders);
            }

            return result;
        }
    }

    @Deprecated
    public void paintAsLayer(RenderingContext c, BlockBox startingPoint) {
        BoxRangeLists rangeLists = new BoxRangeLists();

        List<Box> blocks = new ArrayList<>();
        List<Box> lines = new ArrayList<>();

        BoxCollector collector = new BoxCollector();
        collector.collect(c, c.getOutputDevice().getClip(),
                this, startingPoint, blocks, lines, rangeLists);

        Map<TableCellBox, List<CollapsedBorderSide>> collapsedTableBorders = collectCollapsedTableBorders(c, blocks);

        paintBackgroundsAndBorders(c, blocks, collapsedTableBorders, rangeLists);
        paintListMarkers(c, blocks, rangeLists);
        paintInlineContent(c, lines, rangeLists);
        paintSelection(c, lines); // XXX only do when there is a selection
        paintReplacedElements(c, blocks, rangeLists);
    }

    @Deprecated
    private void paintListMarkers(RenderingContext c, List<Box> blocks, BoxRangeLists rangeLists) {
        BoxRangeHelper helper = new BoxRangeHelper(c.getOutputDevice(), rangeLists.getBlock());

        for (int i = 0; i < blocks.size(); i++) {
            helper.popClipRegions(c, i);

            if (blocks.get(i) instanceof BlockBox) {
                ((BlockBox) blocks.get(i)).paintListMarker(c);
            }

            helper.pushClipRegion(c, i);
        }

        helper.popClipRegions(c, blocks.size());
    }

    @Deprecated
    private void paintReplacedElements(RenderingContext c, List<Box> blocks, BoxRangeLists rangeLists) {
        BoxRangeHelper helper = new BoxRangeHelper(c.getOutputDevice(), rangeLists.getBlock());

        for (int i = 0; i < blocks.size(); i++) {
            helper.popClipRegions(c, i);

            BlockBox box = (BlockBox)blocks.get(i);
            if (box.isReplaced()) {
                paintReplacedElement(c, box);
            }

            helper.pushClipRegion(c, i);
        }

        helper.popClipRegions(c, blocks.size());
    }

    @Deprecated
    private void paintLayerBackgroundAndBorder(RenderingContext c) {
        if (getMaster() instanceof BlockBox) {
            BlockBox box = (BlockBox) getMaster();
            box.paintBackground(c);
            box.paintBorder(c);
        }
    }

    public void positionFixedLayer(RenderingContext c) {
        Rectangle rect = c.getFixedRectangle();

        Box fixed = getMaster();

        fixed.setX(0);
        fixed.setY(0);
        fixed.setAbsX(0);
        fixed.setAbsY(0);

        fixed.setContainingBlock(new ViewportBox(rect));
        ((BlockBox)fixed).positionAbsolute(c, BlockBox.POSITION_BOTH);

        fixed.calcPaintingInfo(c, false);
    }

    public boolean isRootLayer() {
        return getParent() == null && isStackingContext();
    }

    private void moveIfGreater(Dimension result, Dimension test) {
        if (test.width > result.width) {
            result.width = test.width;
        }
        if (test.height > result.height) {
            result.height = test.height;
        }
    }
    
    @Deprecated
    private void paintReplacedElement(RenderingContext c, BlockBox replaced) {
        Rectangle contentBounds = replaced.getContentAreaEdge(
                replaced.getAbsX(), replaced.getAbsY(), c);
        // Minor hack:  It's inconvenient to adjust for margins, border, padding during
        // layout so just do it here.
        Point loc = replaced.getReplacedElement().getLocation();
        if (contentBounds.x != loc.x || contentBounds.y != loc.y) {
            replaced.getReplacedElement().setLocation(contentBounds.x, contentBounds.y);
        }
        if (! c.isInteractive() || replaced.getReplacedElement().isRequiresInteractivePaint()) {
            c.getOutputDevice().paintReplacedElement(c, replaced);
        }
    }

    public void positionChildren(LayoutContext c) {
        for (Layer child : getChildren()) {
            child.position(c);
        }
    }

    private PaintingInfo calcPaintingDimension(LayoutContext c) {
        getMaster().calcPaintingInfo(c, true);
        PaintingInfo result = (PaintingInfo)getMaster().getPaintingInfo().copyOf();

        for (Layer child : getChildren()) {
            if (child.getMaster().getStyle().isFixed()) {
                continue;
            } else if (child.getMaster().getStyle().isAbsolute()) {
                PaintingInfo info = child.calcPaintingDimension(c);
                moveIfGreater(result.getOuterMarginCorner(), info.getOuterMarginCorner());
            }
        }

        return result;
    }

    @Deprecated // Not used.
    private boolean containsFixedLayer() {
        for (Layer child : getChildren()) {
            if (child.getMaster().getStyle().isFixed() || child.containsFixedLayer()) {
                return true;
            }
        }
        return false;
    }

    @Deprecated
    public boolean containsFixedContent() {
        return _fixedBackground || containsFixedLayer();
    }

    @Deprecated // We not longer support fixed background.
    public void setFixedBackground(boolean b) {
        _fixedBackground = b;
    }

    /**
     * The resulting list should not be modified.
     */
    public List<Layer> getChildren() {
        return _children == null ? Collections.emptyList() : _children;
    }

    private void remove(Layer layer) {
        boolean removed = false;

        if (_children != null) {
                for (Iterator<Layer> i = _children.iterator(); i.hasNext(); ) {
                    Layer child = i.next();
                    if (child == layer) {
                        removed = true;
                        i.remove();
                        break;
                    }
                }
        }

        if (! removed) {
            throw new RuntimeException("Could not find layer to remove");
        }
    }

    public void detach() {
        if (getParent() != null) {
            getParent().remove(this);
        }
        setForDeletion(true);
    }

    public boolean isInline() {
        return _inline;
    }

    public void setInline(boolean inline) {
        _inline = inline;
    }

    public Box getEnd() {
        return _end;
    }

    public void setEnd(Box end) {
        _end = end;
    }

    public boolean isRequiresLayout() {
        return _requiresLayout;
    }

    public void setRequiresLayout(boolean requiresLayout) {
        _requiresLayout = requiresLayout;
    }

    public void finish(LayoutContext c) {
        if (c.isPrint()) {
            layoutAbsoluteChildren(c);
        }
        if (! isInline()) {
            positionChildren(c);
        }
    }
    
    private void layoutAbsoluteChildren(LayoutContext c) {
        List<Layer> children = getChildren();
        
        if (children.size() > 0) {
            LayoutState state = c.captureLayoutState();
            
            for (int i = 0; i < children.size(); i++) {
                Layer child = children.get(i);
                boolean isFixed = child.getMaster().getStyle().isFixed();

                if (child.isRequiresLayout()) {
                    layoutAbsoluteChild(c, child);
                    
                    if (!isFixed &&
                        child.getMaster().getStyle().isAvoidPageBreakInside() &&
                        child.getMaster().crossesPageBreak(c)) {
                        
                        BlockBox master = (BlockBox) child.getMaster();
                        
                        master.reset(c);
                        master.setNeedPageClear(true);
                        
                        layoutAbsoluteChild(c, child);
                        
                        if (master.crossesPageBreak(c)) {
                            master.reset(c);
                            layoutAbsoluteChild(c, child);
                        }
                    }
                    
                    child.setRequiresLayout(false);
                    child.finish(c);
                    
                    if (!isFixed) {
                        c.getRootLayer().ensureHasPage(c, child.getMaster());
                    }
                }
            }
            
            c.restoreLayoutState(state);
        }
    }

    private void position(LayoutContext c) {

        if (getMaster().getStyle().isAbsolute() && ! c.isPrint()) {
            ((BlockBox)getMaster()).positionAbsolute(c, BlockBox.POSITION_BOTH);
        } else if (getMaster().getStyle().isRelative() &&
                (isInline() || ((BlockBox)getMaster()).isInline())) {
            getMaster().positionRelative(c);
            if (! isInline()) {
                getMaster().calcCanvasLocation();
                getMaster().calcChildLocations();
            }

        }
    }

    public List<PageBox> getPages() {
		if (_pages == null)
			return _parent == null ? Collections. emptyList() : _parent.getPages();
		return _pages;
    }

    public void setPages(List<PageBox> pages) {
        _pages = pages;
    }

    public boolean isLastPage(PageBox pageBox) {
        return _pages.get(_pages.size()-1) == pageBox;
    }

    private void layoutAbsoluteChild(LayoutContext c, Layer child) {
        BlockBox master = (BlockBox)child.getMaster();

        if (child.getMaster().getStyle().isBottomAuto()) {
            // Set top, left
            master.positionAbsolute(c, BlockBox.POSITION_BOTH);
            master.positionAbsoluteOnPage(c);
            c.reInit(true);
            ((BlockBox)child.getMaster()).layout(c);
            // Set right
            master.positionAbsolute(c, BlockBox.POSITION_HORIZONTALLY);
        } else {
            // FIXME Not right in the face of pagination, but what
            // to do?  Not sure if just laying out and positioning
            // repeatedly will converge on the correct position,
            // so just guess for now
            c.reInit(true);
            master.layout(c);

            BoxDimensions before = master.getBoxDimensions();
            master.reset(c);
            BoxDimensions after = master.getBoxDimensions();
            master.setBoxDimensions(before);
            master.positionAbsolute(c, BlockBox.POSITION_BOTH);
            master.positionAbsoluteOnPage(c);
            master.setBoxDimensions(after);

            c.reInit(true);
            ((BlockBox)child.getMaster()).layout(c);
        }
    }

    public void removeLastPage() {
        PageBox pageBox = _pages.remove(_pages.size()-1);
        if (pageBox == getLastRequestedPage()) {
            setLastRequestedPage(null);
        }
    }

    public void addPage(CssContext c) {
        String pseudoPage = null;
        if (_pages == null) {
            _pages = new ArrayList<>();
        }

        List<PageBox> pages = getPages();
        if (pages.size() == 0) {
            pseudoPage = "first";
        } else if (pages.size() % 2 == 0) {
            pseudoPage = "right";
        } else {
            pseudoPage = "left";
        }
        PageBox pageBox = createPageBox(c, pseudoPage);
        if (pages.size() == 0) {
            pageBox.setTopAndBottom(c, 0);
        } else {
            PageBox previous = pages.get(pages.size()-1);
            pageBox.setTopAndBottom(c, previous.getBottom());
        }

        pageBox.setPageNo(pages.size());
        pages.add(pageBox);
    }

    public PageBox getFirstPage(CssContext c, int absY) {
        return getPage(c, absY);
    }

    public PageBox getFirstPage(CssContext c, Box box) {
        return getPage(c, box.getAbsY());
    }

    public PageBox getLastPage(CssContext c, Box box) {
        return getPage(c, box.getAbsY() + box.getHeight() - 1);
    }

    public void ensureHasPage(CssContext c, Box box) {
        getLastPage(c, box);
    }

    public PageBox getPage(CssContext c, int yOffset) {
        List<PageBox> pages = getPages();
        if (yOffset < 0) {
            return null;
        } else {
            PageBox lastRequested = getLastRequestedPage();
            if (lastRequested != null) {
                if (yOffset >= lastRequested.getTop() && yOffset < lastRequested.getBottom()) {
                    return lastRequested;
                }
            }
            PageBox last = pages.get(pages.size()-1);
            if (yOffset < last.getBottom()) {
                // The page we're looking for is probably at the end of the
                // document so do a linear search for the first few pages
                // and then fall back to a binary search if that doesn't work
                // out
                int count = pages.size();
                for (int i = count-1; i >= 0 && i >= count-5; i--) {
                    PageBox pageBox = (PageBox)pages.get(i);
                    if (yOffset >= pageBox.getTop() && yOffset < pageBox.getBottom()) {
                        setLastRequestedPage(pageBox);
                        return pageBox;
                    }
                }

                int low = 0;
                int high = count-6;

                while (low <= high) {
                    int mid = (low + high) >> 1;
                    PageBox pageBox = (PageBox)pages.get(mid);

                    if (yOffset >= pageBox.getTop() && yOffset < pageBox.getBottom()) {
                        setLastRequestedPage(pageBox);
                        return pageBox;
                    }

                    if (pageBox.getTop() < yOffset) {
                        low = mid + 1;
                    } else {
                        high = mid - 1;
                    }
                }
            } else {
                addPagesUntilPosition(c, yOffset);
                PageBox result = (PageBox) pages.get(pages.size()-1);
                setLastRequestedPage(result);
                return result;
            }
        }

        throw new RuntimeException("internal error");
    }

    private void addPagesUntilPosition(CssContext c, int position) {
        List<PageBox> pages = getPages();
        PageBox last = pages.get(pages.size()-1);
        while (position >= last.getBottom()) {
            addPage(c);
            last = pages.get(pages.size()-1);
        }
    }

    public void trimEmptyPages(CssContext c, int maxYHeight) {
        // Empty pages may result when a "keep together" constraint
        // cannot be satisfied and is dropped
        List<PageBox> pages = getPages();
        for (int i = pages.size() - 1; i > 0; i--) {
            PageBox page = pages.get(i);
            if (page.getTop() >= maxYHeight) {
                if (page == getLastRequestedPage()) {
                    setLastRequestedPage(null);
                }
                pages.remove(i);
            } else {
                break;
            }
        }
    }

    public void trimPageCount(int newPageCount) {
        while (_pages.size() > newPageCount) {
            PageBox pageBox = _pages.remove(_pages.size()-1);
            if (pageBox == getLastRequestedPage()) {
                setLastRequestedPage(null);
            }
        }
    }

    public void assignPagePaintingPositions(CssContext cssCtx, short mode) {
        assignPagePaintingPositions(cssCtx, mode, 0);
    }

    public void assignPagePaintingPositions(
            CssContext cssCtx, int mode, int additionalClearance) {
        List<PageBox> pages = getPages();
        int paintingTop = additionalClearance;
        for (PageBox page : pages) {
            page.setPaintingTop(paintingTop);
            if (mode == PAGED_MODE_SCREEN) {
                page.setPaintingBottom(paintingTop + page.getHeight(cssCtx));
            } else if (mode == PAGED_MODE_PRINT) {
                page.setPaintingBottom(paintingTop + page.getContentHeight(cssCtx));
            } else {
                throw new IllegalArgumentException("Illegal mode");
            }
            paintingTop = page.getPaintingBottom() + additionalClearance;
        }
    }

    public int getMaxPageWidth(CssContext cssCtx, int additionalClearance) {
        List<PageBox> pages = getPages();
        int maxWidth = 0;
        for (PageBox page : pages) {
            int pageWidth = page.getWidth(cssCtx) + additionalClearance * 2;
            if (pageWidth > maxWidth) {
                maxWidth = pageWidth;
            }
        }

        return maxWidth;
    }

    public PageBox getLastPage() {
        List<PageBox> pages = getPages();
        return pages.size() == 0 ? null : pages.get(pages.size()-1);
    }

    public boolean crossesPageBreak(LayoutContext c, int top, int bottom) {
        if (top < 0) {
            return false;
        }
        PageBox page = getPage(c, top);
        return bottom >= page.getBottom() - c.getExtraSpaceBottom();
    }

    public Layer findRoot() {
        if (isRootLayer()) {
            return this;
        } else {
            return getParent().findRoot();
        }
    }

    public void addRunningBlock(BlockBox block) {
        if (_runningBlocks == null) {
            _runningBlocks = new HashMap<>();
        }

        String identifier = block.getStyle().getRunningName();

        List<BlockBox> blocks = _runningBlocks.get(identifier);
        if (blocks == null) {
            blocks = new ArrayList<>();
            _runningBlocks.put(identifier, blocks);
        }

        blocks.add(block);

        Collections.sort(blocks, (b1, b2) -> b1.getAbsY() - b2.getAbsY());
    }

    public void removeRunningBlock(BlockBox block) {
        if (_runningBlocks == null) {
            return;
        }

        String identifier = block.getStyle().getRunningName();

        List<BlockBox> blocks = _runningBlocks.get(identifier);
        if (blocks == null) {
            return;
        }

        blocks.remove(block);
    }

    public BlockBox getRunningBlock(String identifer, PageBox page, PageElementPosition which) {
        if (_runningBlocks == null) {
            return null;
        }

        List<BlockBox> blocks = _runningBlocks.get(identifer);
        if (blocks == null) {
            return null;
        }

        if (which == PageElementPosition.START) {
            BlockBox prev = null;
            for (Iterator<BlockBox> i = blocks.iterator(); i.hasNext(); ) {
                BlockBox b = i.next();
                if (b.getStaticEquivalent().getAbsY() >= page.getTop()) {
                    break;
                }
                prev = b;
            }
            return prev;
        } else if (which == PageElementPosition.FIRST) {
            for (Iterator<BlockBox> i = blocks.iterator(); i.hasNext(); ) {
                BlockBox b = i.next();
                int absY = b.getStaticEquivalent().getAbsY();
                if (absY >= page.getTop() && absY < page.getBottom()) {
                    return b;
                }
            }
            return getRunningBlock(identifer, page, PageElementPosition.START);
        } else if (which == PageElementPosition.LAST) {
            BlockBox prev = null;
            for (Iterator<BlockBox> i = blocks.iterator(); i.hasNext(); ) {
                BlockBox b = i.next();
                if (b.getStaticEquivalent().getAbsY() > page.getBottom()) {
                    break;
                }
                prev = b;
            }
            return prev;
        } else if (which == PageElementPosition.LAST_EXCEPT) {
            BlockBox prev = null;
            for (Iterator<BlockBox> i = blocks.iterator(); i.hasNext(); ) {
                BlockBox b = i.next();
                int absY = b.getStaticEquivalent().getAbsY();
                if (absY >= page.getTop() && absY < page.getBottom()) {
                    return null;
                }
                if (absY > page.getBottom()) {
                    break;
                }
                prev = b;
            }
            return prev;
        }

        throw new RuntimeException("bug: internal error");
    }

    public void layoutPages(LayoutContext c) {
        c.setRootDocumentLayer(c.getRootLayer());
        for (PageBox pageBox : _pages) {
            pageBox.layout(c);
        }
    }

    public void addPageSequence(BlockBox start) {
        if (_pageSequences == null) {
            _pageSequences = new HashSet<>();
        }

        _pageSequences.add(start);
    }

    private List<BlockBox> getSortedPageSequences() {
        if (_pageSequences == null) {
            return null;
        }

        if (_sortedPageSequences == null) {
            List<BlockBox> result = new ArrayList<>(_pageSequences);

            Collections.sort(result, new Comparator<BlockBox>() {
                public int compare(BlockBox b1, BlockBox b2) {
                    return b1.getAbsY() - b2.getAbsY();
                }
            });

            _sortedPageSequences  = result;
        }

        return _sortedPageSequences;
    }

    public int getRelativePageNo(RenderingContext c, int absY) {
        List<BlockBox> sequences = getSortedPageSequences();
        int initial = 0;
        if (c.getInitialPageNo() > 0) {
            initial = c.getInitialPageNo() - 1;
        }
        if ((sequences == null) || sequences.isEmpty()) {
            return initial + getPage(c, absY).getPageNo();
        } else {
            BlockBox pageSequence = findPageSequence(sequences, absY);
            int sequenceStartAbsolutePageNo = getPage(c, pageSequence.getAbsY()).getPageNo();
            int absoluteRequiredPageNo = getPage(c, absY).getPageNo();
            return absoluteRequiredPageNo - sequenceStartAbsolutePageNo;
        }
    }

    private BlockBox findPageSequence(List<BlockBox> sequences, int absY) {
        BlockBox result = null;

        for (int i = 0; i < sequences.size(); i++) {
            result = sequences.get(i);
            if ((i < sequences.size() - 1) && ((sequences.get(i + 1)).getAbsY() > absY)) {
                break;
            }
        }

        return result;
    }

    public int getRelativePageNo(RenderingContext c) {
        List<BlockBox> sequences = getSortedPageSequences();
        int initial = 0;
        if (c.getInitialPageNo() > 0) {
            initial = c.getInitialPageNo() - 1;
        }
        if (sequences == null) {
            return initial + c.getPageNo();
        } else {
            int sequenceStartIndex = getPageSequenceStart(c, sequences, c.getPage());
            if (sequenceStartIndex == -1) {
                return initial + c.getPageNo();
            } else {
                BlockBox block = sequences.get(sequenceStartIndex);
                return c.getPageNo() - getFirstPage(c, block).getPageNo();
            }
        }
    }

    public int getRelativePageCount(RenderingContext c) {
        List<BlockBox> sequences = getSortedPageSequences();
        int initial = 0;
        if (c.getInitialPageNo() > 0) {
            initial = c.getInitialPageNo() - 1;
        }
        if (sequences == null) {
            return initial + c.getPageCount();
        } else {
            int firstPage;
            int lastPage;

            int sequenceStartIndex = getPageSequenceStart(c, sequences, c.getPage());

            if (sequenceStartIndex == -1) {
                firstPage = 0;
            } else {
                BlockBox block = sequences.get(sequenceStartIndex);
                firstPage = getFirstPage(c, block).getPageNo();
            }

            if (sequenceStartIndex < sequences.size() - 1) {
                BlockBox block = sequences.get(sequenceStartIndex+1);
                lastPage = getFirstPage(c, block).getPageNo();
            } else {
                lastPage = c.getPageCount();
            }

            int sequenceLength = lastPage - firstPage;
            if (sequenceStartIndex == -1) {
                sequenceLength += initial;
            }

            return sequenceLength;
        }
    }

    private int getPageSequenceStart(RenderingContext c, List<BlockBox> sequences, PageBox page) {
        for (int i = sequences.size() - 1; i >= 0; i--) {
            BlockBox start = sequences.get(i);
            if (start.getAbsY() < page.getBottom() - 1) {
                return i;
            }
        }

        return -1;
    }

    public Box getSelectionEnd() {
        return _selectionEnd;
    }

    public void setSelectionEnd(Box selectionEnd) {
        _selectionEnd = selectionEnd;
    }

    public Box getSelectionStart() {
        return _selectionStart;
    }

    public void setSelectionStart(Box selectionStart) {
        _selectionStart = selectionStart;
    }

    public int getSelectionEndX() {
        return _selectionEndX;
    }

    public void setSelectionEndX(int selectionEndX) {
        _selectionEndX = selectionEndX;
    }

    public int getSelectionEndY() {
        return _selectionEndY;
    }

    public void setSelectionEndY(int selectionEndY) {
        _selectionEndY = selectionEndY;
    }

    public int getSelectionStartX() {
        return _selectionStartX;
    }

    public void setSelectionStartX(int selectionStartX) {
        _selectionStartX = selectionStartX;
    }

    public int getSelectionStartY() {
        return _selectionStartY;
    }

    public void setSelectionStartY(int selectionStartY) {
        _selectionStartY = selectionStartY;
    }

    private PageBox getLastRequestedPage() {
        return _lastRequestedPage;
    }

    private void setLastRequestedPage(PageBox lastRequestedPage) {
        _lastRequestedPage = lastRequestedPage;
    }
}
