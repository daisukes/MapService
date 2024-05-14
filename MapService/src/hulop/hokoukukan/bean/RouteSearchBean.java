/*******************************************************************************
 * Copyright (c) 2014, 2017  IBM Corporation, Carnegie Mellon University and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *******************************************************************************/
package hulop.hokoukukan.bean;

import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;
import org.jgrapht.WeightedGraph;
import org.jgrapht.alg.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;

import hulop.hokoukukan.servlet.RouteSearchServlet;
import hulop.hokoukukan.utils.DBAdapter;
import hulop.hokoukukan.utils.Hokoukukan;

public class RouteSearchBean {

	public static final DBAdapter adapter = DatabaseBean.adapter;
	private static final double WEIGHT_IGNORE = Double.MAX_VALUE;
	private static final double ESCALATOR_WEIGHT = RouteSearchServlet.getEnvInt("ESCALATOR_WEIGHT", 100);
	private static final double STAIR_WEIGHT = RouteSearchServlet.getEnvInt("STAIR_WEIGHT", 300);
	private static final double ELEVATOR_WEIGHT = RouteSearchServlet.getEnvInt("ELEVATOR_WEIGHT", 300);
	private long mLastInit = System.currentTimeMillis();
	private JSONObject mNodeMap, mNodeFacilities, mTempNode, mTempLink1, mTempLink2;
	private JSONArray mFeatures, mLandmarks, mDoors;
	private Set<String> mElevatorNodes;
	private RouteData routeCache;

	public RouteSearchBean() {
	}

	public void init(double[] point, double distance, String lang, boolean cache) throws JSONException {
		System.out.println("RouteSearchBean init lang=" + lang);
		RouteData rd = cache ? RouteData.getCache(point, distance) : new RouteData(point, distance);
		mTempNode = mTempLink1 = mTempLink2 = null;
		mNodeMap = rd.getNodeMap();
		mNodeFacilities = rd.getNodeFacilities();
		mFeatures = rd.getFeatures();
		mLandmarks = rd.getLandmarks(lang);
		mDoors = rd.getDoors();
		mElevatorNodes = rd.getElevatorNodes();
		mLastInit = System.currentTimeMillis();
		routeCache = cache ? rd : null;
	}

	public boolean isValid() {
		return routeCache != null ? RouteData.isCacheValid(routeCache) : true;
	}

	public long getLastInit() {
		return mLastInit;
	}

	public JSONObject getNodeMap() {
		return mNodeMap;
	}

	public JSONArray getFeatures() {
		return mFeatures;
	}

	public JSONArray getLandmarks() {
		return mLandmarks;
//		JSONArray result = new JSONArray();
//		for (JSONObject obj : (List<JSONObject>)mLandmarks) {
//			try {
//				if (Hokoukukan.available(obj.getJSONObject("properties"))) {
//					result.put(obj);
//				}
//			} catch (JSONException e) {
//				e.printStackTrace();
//			}
//		}
//		return result;
	}

	public Object getDirection(String from, String to, Map<String, String> conditions) throws JSONException {
		mLastInit = System.currentTimeMillis();
		mTempNode = mTempLink1 = mTempLink2 = null;
		JSONObject fromPoint = getPoint(from), toPoint = getPoint(to);
		if (fromPoint != null) {
			from = findNearestLink(fromPoint);
			if (from == null) {
				return null;
			}
			if (!mNodeMap.has(from)) {
				for (Object feature : mFeatures) {
					JSONObject json = (JSONObject) feature;
					if (from.equals(json.get("_id"))) {
						try {
							from = createTempNode(fromPoint, json);
						} catch (Exception e) {
							e.printStackTrace();
						}
						break;
					}
				}
			}
		} else {
			from = extractNode(from);
		}
		if (toPoint != null) {
			to = adapter.findNearestNode(new double[] { toPoint.getDouble("lng"), toPoint.getDouble("lat") },
					toPoint.has("floors") ? toPoint.getJSONArray("floors") : null);
		}
		if (from != null && from.equals(extractNode(to))) {
			return new JSONObject().put("error", "zero-distance");
		}
		DirectionHandler dh = new DirectionHandler(from, to, conditions);
		for (Object feature : mFeatures) {
			dh.add(feature);
		}
		if (mTempNode != null) {
			dh.add(mTempLink1);
			dh.add(mTempLink2);
		}
		return addStartArea(dh.getResult(), fromPoint);
	}

	private class DirectionHandler {
		private WeightedGraph<String, DefaultWeightedEdge> g = new SimpleDirectedWeightedGraph<String, DefaultWeightedEdge>(
				DefaultWeightedEdge.class);
		private Map<Object, JSONObject> linkMap = new HashMap<Object, JSONObject>();
		private JSONArray result = new JSONArray();
		private String from, to;
		private Map<String, String> conditions;
		private final double elevator_weight;

		public DirectionHandler(String from, String to, Map<String, String> conditions) {
			this.from = from;
			this.to = to;
			this.conditions = conditions;
			this.elevator_weight = ELEVATOR_WEIGHT * ("8".equals(conditions.get("elv")) ? 10 : 1);
		}

		public void add(Object feature) throws JSONException {
			JSONObject json = (JSONObject) feature;
			JSONObject properties = json.getJSONObject("properties");
			if (properties.has("リンクID") && Hokoukukan.available(properties)) {
				double weight = 10.0f;
				try {
					weight = Double.parseDouble(properties.getString("リンク延長"));
					if (properties.has("road_low_priority") && "1".equals(properties.getString("road_low_priority"))) {
						weight *= 1.25;
					}
				} catch (Exception e) {
				}
				weight = adjustAccWeight(properties, weight);
				if (weight == WEIGHT_IGNORE) {
					return;
				}
				String start, end;
				try {
					start = properties.getString("起点ノードID");
					end = properties.getString("終点ノードID");
				} catch (Exception e) {
					return;
				}
				if (from == null) {
					try {
						properties.put("sourceHeight", getHeight(start));
						properties.put("targetHeight", getHeight(end));
					} catch (Exception e) {
					}
					result.add(json);
					return;
				}
				g.addVertex(start);
				g.addVertex(end);

				DefaultWeightedEdge startEnd = null, endStart = null;
				switch (properties.getString("方向性")) {
				case "1":
					startEnd = g.addEdge(start, end);
					break;
				case "2":
					endStart = g.addEdge(end, start);
					break;
				default:
					startEnd = g.addEdge(start, end);
					endStart = g.addEdge(end, start);
					break;
				}
				if (startEnd != null) {
					double add = !mElevatorNodes.contains(start) && mElevatorNodes.contains(end) ? elevator_weight : 0;
					g.setEdgeWeight(startEnd, weight + add);
					linkMap.put(startEnd, json);
				}
				if (endStart != null) {
					double add = mElevatorNodes.contains(start) && !mElevatorNodes.contains(end) ? elevator_weight : 0;
					g.setEdgeWeight(endStart, weight + add);
					linkMap.put(endStart, json);
				}
			}
		}

		public JSONArray getResult() {
			if (from != null) {
				try {
					// System.out.println(from + " - " + to + " - " + g.toString());
					double lastWeight = Double.MAX_VALUE;
					List<DefaultWeightedEdge> path = null;
					mainLoop: for (String t : to.split("\\|")) {
						t = t.trim();
						if (t.length() > 0) {
							try {
								String node_id = extractNode(t);
								if (mNodeFacilities.has(node_id) && !Hokoukukan.availableAny(mNodeFacilities.getJSONArray(node_id))) {
									continue;
								}
								for (Object _obj:mLandmarks) {
									JSONObject obj = (JSONObject)_obj;
									if (obj.has("node") && node_id.equals(obj.getString("node")) && obj.has("exit_brr")) {
										String exit_brr = obj.getString("exit_brr");
										// 0: <2cm, 1: 2 to 5cm, 2: 5 to 10cm, 3: 10cm or more, 9: unknown
										try {
											switch (conditions.get("deff_LV")) {
											case "1": // <2cm
												if (exit_brr.equals("1")) {
													continue mainLoop;
												}
												//$FALL-THROUGH$
											case "2": // <5cm
												if (exit_brr.equals("2")) {
													continue mainLoop;
												}
												//$FALL-THROUGH$
											case "3": // <10cm
												if (exit_brr.equals("3")) {
													continue mainLoop;
												}
												break;
											}
										} catch (NullPointerException npe) {
										}
									}
								}
								List<DefaultWeightedEdge> p = DijkstraShortestPath.findPathBetween(g, from, node_id);
								if (p != null && p.size() > 0) {
									double totalWeight = 0;
									for (DefaultWeightedEdge edge : p) {
										totalWeight += g.getEdgeWeight(edge);
									}
									if (lastWeight > totalWeight) {
										lastWeight = totalWeight;
										path = p;
										to = t;
									}
								}
							} catch (Exception e) {
								System.err.println("No route to " + t);
							}
						}
					}
					if (path != null && path.size() > 0) {
						JSONObject fromNode = (JSONObject) getNode(from).clone();
						result.add(fromNode);
						for (DefaultWeightedEdge edge : path) {
							JSONObject link = linkMap.get(edge);
							try {
								link = new JSONObject(link.toString()); // deep clone
								JSONObject properties = link.getJSONObject("properties");
								String edgeSource = g.getEdgeSource(edge);
								String edgeTarget = g.getEdgeTarget(edge);
								String sourceDoor = getDoor(edgeSource);
								String targetDoor = getDoor(edgeTarget);
								properties.put("sourceNode", edgeSource);
								properties.put("targetNode", edgeTarget);
								properties.put("sourceHeight", getHeight(edgeSource));
								properties.put("targetHeight", getHeight(edgeTarget));
								if (sourceDoor != null) {
									properties.put("sourceDoor", sourceDoor);
								} else {
									properties.remove("sourceDoor");
								}
								if (targetDoor != null) {
									properties.put("targetDoor", targetDoor);
								} else {
									properties.remove("targetDoor");
								}
							} catch (Exception e) {
								e.printStackTrace();
							}
							result.add(link);
						}
						JSONObject toNode = (JSONObject) getNode(extractNode(to)).clone();
						toNode.put("_id", to);
						result.add(toNode);
					}
					// System.out.println(new KShortestPaths(g, from,
					// 3).getPaths(to));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			return result;
		}

		private double adjustAccWeight(JSONObject properties, double weight)
				throws JSONException {

			String linkType = properties.has("経路の種類") ? properties.getString("経路の種類") : "";
			switch (linkType) {
			case "10": // Elevator
				weight = 0.0f;
				break;
			case "7": // Moving walkway
				weight *= 0.75f;
				break;
			case "11": // Escalator
				weight = ESCALATOR_WEIGHT;
				break;
			case "12": // Stairs
				weight = STAIR_WEIGHT;
				break;
			}
			try {
				weight = Double.parseDouble(properties.getString("distance_overwrite"));
			} catch (Exception e) {
			}
			double penarty = Math.max(weight, 10.0f) * 9;

			String width = properties.has("有効幅員") ? properties.getString("有効幅員") : "";
			// 0: less than 1m,
			// 1: >1m & <1.5m,
			// 2: >1.5m & <2m,
			// 3: >2m
			// 9: unknown
			try {
				switch (conditions.get("min_width")) {
				case "1": // >2m
					if (width.equals("0") || width.equals("1") || width.equals("2") || width.equals("9")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "2": // >1.5m
					if (width.equals("0") || width.equals("1") || width.equals("9")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "3": // >1.0m
					if (width.equals("0") || width.equals("9")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "8": // Avoid
					if (width.equals("0") || width.equals("1") || width.equals("2") || width.equals("9")) {
						weight += penarty;
					}
					break;
				}
			} catch (NullPointerException npe) {
			}

			float slope = 0;
			try {
				slope = Float.parseFloat(properties.getString("縦断勾配1"));
			} catch (Exception e) {
			}
			// Maximum slope value (%) along the link
			try {
				switch (conditions.get("slope")) {
				case "1": // <8%
					if (slope >= 8.0) {
						return WEIGHT_IGNORE;
					}
					break;
				case "2": // <10%
					if (slope >= 10.0) {
						return WEIGHT_IGNORE;
					}
					break;
				case "8": // Avoid
					if (slope >= 8.0) {
						weight += penarty;
					}
					break;
				}
			} catch (NullPointerException npe) {
			}

			String road = properties.has("路面状況") ? properties.getString("路面状況") : "";
			// 0: no problem, 1: dirt, 2: gravel, 3: other, 9: unknown
			try {
				switch (conditions.get("road_condition")) {
				case "1": // No problem
					if (road.equals("1") || road.equals("2") || road.equals("3") || road.equals("9")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "8": // Avoid
					if (road.equals("1") || road.equals("2") || road.equals("3") || road.equals("9")) {
						weight += penarty;
					}
					break;
				}
			} catch (NullPointerException npe) {
			}

			String bump = properties.has("段差") ? properties.getString("段差") : "";
			// 0: less than 2cm, 1: 2~5cm, 2: 5~10cm, 3: more than 10cm, 9: unknown
			// (assign max bump height for whole link)
			try {
				switch (conditions.get("deff_LV")) {
				case "1": // <2cm
					if (bump.equals("1") || bump.equals("2") || bump.equals("3") || bump.equals("9")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "2": // <5cm
					if (bump.equals("2") || bump.equals("3") || bump.equals("9")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "3": // <10cm
					if (bump.equals("3") || bump.equals("9")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "8": // Avoid
					if (bump.equals("1") || bump.equals("2") || bump.equals("3") || bump.equals("9")) {
						weight += penarty;
					}
					break;
				}
			} catch (NullPointerException npe) {
			}

			int steps = 0;
			try {
				steps = Integer.parseInt(properties.getString("最小階段段数"));
			} catch (Exception e) {
			}
			// number of steps along a stairway
			if (linkType.equals("12") && steps == 0) {
			// System.out.println("Error: steps should > 0");
				steps = 1;
			}
			String rail = properties.has("手すり") ? properties.getString("手すり") : "";
			// 0: no, 1: on the right, 2: on the left, 3: both sides, 9: unknown
			// (link direction - start node to end node)
			try {
				switch (conditions.get("stairs")) {
				case "1": // Do not use
					if (steps > 0) {
						return WEIGHT_IGNORE;
					}
					break;
				case "2": // Use with hand rail
					if (steps > 0 && !(rail.equals("1") || rail.equals("2") || rail.equals("3"))) {
						return WEIGHT_IGNORE;
					}
					break;
				case "8": // Avoid
					if (steps > 0) {
						weight += penarty;
					}
					break;
				}
			} catch (NullPointerException npe) {
			}

			String elevator = properties.has("エレベーター種別") ? properties.getString("エレベーター種別") : "";
			// 0: not included, 1: braille and audio, 2: wheelchair, 3: 1&2, 9:
			// unknown
			try {
				switch (conditions.get("elv")) {
				case "1": // Do not use
					if (elevator.equals("0") || elevator.equals("1") || elevator.equals("2") || elevator.equals("3")
							|| elevator.equals("9")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "2": // Wheel chair supported
					if (elevator.equals("0") || elevator.equals("1") || elevator.equals("9")) {
						return WEIGHT_IGNORE;
					}
					break;
				}
			} catch (NullPointerException npe) {
			}

			try {
				switch (conditions.get("esc")) {
				case "1": // Do not use
					if (linkType.equals("11")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "8": // Avoid
					if (linkType.equals("11")) {
						weight += penarty;
					}
					break;
				}
			} catch (NullPointerException npe) {
			}

			try {
				switch (conditions.get("mvw")) {
				case "1": // Do not use
					if (linkType.equals("7")) {
						return WEIGHT_IGNORE;
					}
					break;
				case "8": // Avoid
					if (linkType.equals("7")) {
						weight += penarty;
					}
					break;
				}
			} catch (NullPointerException npe) {
			}

			if (properties.has("視覚障害者誘導用ブロック") && "1".equals(properties.getString("視覚障害者誘導用ブロック"))) {
				// 0: no, 1: yes, 9: unknown (tactile paving along the path/link)
				if ("1".equals(conditions.get("tactile_paving"))) {
					weight = weight / 3;
				}
			}
			return weight;
		}
	}

	private static String extractNode(String id) {
		return id != null ? id.split(":")[0] : null;
	}

	private static JSONObject getPoint(String node) {
		if (node != null) {
			String[] params = node.split(":");
			if (params.length >= 3 && params[0].equals("latlng")) {
				JSONObject point = new JSONObject();
				try {
					if (params.length > 3) {
						List<String> floors = new ArrayList<String>();
						floors.add(params[3]);
						if (params[3].equals("1")) {
							floors.add("0");
						}
						point.put("floors", floors);
					}
					return point.put("lat", Double.parseDouble(params[1])).put("lng", Double.parseDouble(params[2]));
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
		}
		return null;
	}

	private boolean isNode(String id) {
		return tempNodeID.equals(id) ? mTempNode != null : mNodeMap.has(id);
	}

	private JSONObject getNode(String id) throws JSONException {
		return tempNodeID.equals(id) ? mTempNode : mNodeMap.getJSONObject(id);
	}

	private double getHeight(String node) throws NumberFormatException, JSONException {
		return Double.parseDouble(getNode(node).getJSONObject("properties").getString("高さ").replace("B", "-"));
	}

	private String getDoor(String node) throws JSONException {
		if (countLinks(node) <= 2) {
			for (Object p : mDoors) {
				JSONObject properties = (JSONObject) p;
				if (node.equals(properties.getString("対応ノードID"))) {
					return properties.getString("扉の種類");
				}
			}
		}
		return null;
	}

	private static final Pattern LINK_ID = Pattern.compile("^接続リンクID\\d+$");

	private int countLinks(String node) {
		try {
			int count = 0;
			for (Iterator<String> it = getNode(node).getJSONObject("properties").keys(); it.hasNext();) {
				count += (LINK_ID.matcher(it.next()).matches() ? 1 : 0);
			}
			return count;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return 0;
	}

	private static final double METERS_PER_DEGREE = 60.0 * 1.1515 * 1.609344 * 1000;

	private static double deg2rad(double deg) {
		return (deg * Math.PI / 180.0);
	}

	public static double calcDistance(double[] point1, double[] point2) {
		double theta = deg2rad(point1[0] - point2[0]);
		double lat1 = deg2rad(point1[1]), lat2 = deg2rad(point2[1]);
		double dist = Math.sin(lat1) * Math.sin(lat2) + Math.cos(lat1) * Math.cos(lat2) * Math.cos(theta);
		dist = Math.min(1.0, Math.max(-1.0, dist));
		return METERS_PER_DEGREE * Math.acos(dist) * 180.0 / Math.PI;
	}

	static final String INVALID_LINKS = "|7|10|11|";

	private String findNearestLink(JSONObject fromPoint) {
		try {
			List<String> floors = fromPoint.has("floors") ? fromPoint.getJSONArray("floors") : null;
			List<JSONObject> links = new ArrayList<JSONObject>();
			for (Object feature : mFeatures) {
				JSONObject json = (JSONObject) feature;
				JSONObject properties = json.getJSONObject("properties");
				String startID, endID;
				try {
					startID = properties.getString("起点ノードID");
					endID = properties.getString("終点ノードID");
				} catch (Exception e) {
					continue;
				}
				if (INVALID_LINKS.indexOf("|" + properties.getString("経路の種類") + "|") == -1) {
					if (isNode(startID) && isNode(endID)) {
						if (floors == null) {
							links.add(json);
						} else {
							String startHeight = getNode(startID).getJSONObject("properties").getString("高さ");
							String endHeight = getNode(endID).getJSONObject("properties").getString("高さ");
							if (floors.indexOf(startHeight) != -1 && floors.indexOf(endHeight) != -1) {
								links.add(json);
							}
						}
					}
				}
			}
			if (links.size() > 0) {
				final Point2D.Double pt = new Point2D.Double(fromPoint.getDouble("lng"), fromPoint.getDouble("lat"));
				double minDist = 100;
				JSONObject nearestLink = null;
				for (JSONObject json : links) {
					double dist = calc2Ddistance(json.getJSONObject("geometry").getJSONArray("coordinates"), pt, null);
					if (dist < minDist) {
						minDist = dist;
						nearestLink = json;
					}
				}
				if (nearestLink != null) {
					return nearestLink.getString("_id");
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	private JSONArray addStartArea(JSONArray route, JSONObject startPos) {
		if (startPos == null || route.length() < 2) {
			return route;
		}
		JSONObject lastResult = null;
		Path2D.Double lastPoly = null;
		try {
			List<Object> floors = startPos.has("floors") ? startPos.getJSONArray("floors") : null;
			final Point2D.Double pt = new Point2D.Double(startPos.getDouble("lng"), startPos.getDouble("lat"));
			for (Object feature : mFeatures) {
				JSONObject json = (JSONObject) feature;
				JSONObject properties = json.getJSONObject("properties");
				if (!properties.has("hulop_area_id")) {
					continue;
				}
				JSONObject geometry = json.getJSONObject("geometry");
				if (!"Polygon".equals(geometry.getString("type"))) {
					continue;
				}
				if (floors != null && properties.has("hulop_area_height")) {
					String floor = properties.getString("hulop_area_height");
					if (!floors.contains(floor)) {
						continue;
					}
				}
				JSONArray coordinates = geometry.getJSONArray("coordinates").getJSONArray(0);
				Path2D.Double poly = new Path2D.Double();
				for (int i = 0; i < coordinates.length(); i++) {
					JSONArray array = coordinates.getJSONArray(i);
					double x = array.getDouble(0), y = array.getDouble(1);
					if (i == 0) {
						poly.moveTo(x, y);
					} else {
						poly.lineTo(x, y);
					}
				}
				poly.closePath();
				if (poly.contains(pt) && (lastPoly == null || lastPoly.contains(poly.getBounds2D()))) {
					lastPoly = poly;
					lastResult = json;
				}
			}
			if (lastResult != null) {
				JSONObject from = lastResult.getJSONObject("properties");
				JSONObject to = route.getJSONObject(0).getJSONObject("properties");
				for (Iterator<String> it = from.keys(); it.hasNext();) {
					String key = it.next();
					if (key.startsWith("hulop_area_")) {
						to.put(key, from.get(key));
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return route;
	}

	private static double calc2Ddistance(JSONArray coordinates, Point2D.Double pt, int[] seg) throws Exception {
		double result = -1;
		Point2D.Double from = get2DPoint(coordinates, 0);
		for (int i = 1; i < coordinates.size() && result != 0.0; i++) {
			Point2D.Double to = get2DPoint(coordinates, i);
			double dist = Line2D.ptSegDist(from.x, from.y, to.x, to.y, pt.x, pt.y);
			from = to;
			if (result < 0 || dist < result) {
				result = dist;
				if (seg != null) {
					seg[0] = i - 1;
				}
			}
		}
		return result;
	}

	private static Point2D.Double get2DPoint(JSONArray coordinates, int index) throws Exception {
		JSONArray coord = coordinates.getJSONArray(index);
		// return new Point2D.Double(coord.getDouble(0), coord.getDouble(1));
		return new Point2D.Double(((Double) coord.get(0)).doubleValue(), ((Double) coord.get(1)).doubleValue());
	}

	static final String tempNodeID = "_TEMP_NODE_", tempLink1ID = "_TEMP_LINK1_", tempLink2ID = "_TEMP_LINK2_";

	private String createTempNode(JSONObject point, JSONObject link) throws Exception {
		JSONArray linkCoords = link.getJSONObject("geometry").getJSONArray("coordinates");
		JSONArray nodeCoord = new JSONArray().put(point.getDouble("lng")).put(point.getDouble("lat"));
		Object pos = getOrthoCenter(linkCoords, nodeCoord, link.getJSONObject("properties"));
		int lineSeg = 0;
		if (pos instanceof String) {
			return (String) pos;
		} else if (pos instanceof JSONObject) {
			JSONObject o = (JSONObject) pos;
			nodeCoord = new JSONArray().put(o.getDouble("x")).put(o.getDouble("y"));
			lineSeg = o.getInt("seg");
		}

		// Create temp node
		String tempFloor = point.has("floors") ? point.getJSONArray("floors").getString(0) : "0";
		mTempNode = new JSONObject();
		final JSONObject geometry = new JSONObject();
		final JSONObject nodeProp = new JSONObject();
		geometry.put("type", "Point");
		geometry.put("coordinates", nodeCoord);
		nodeProp.put("category", "ノード情報");
		nodeProp.put("ノードID", tempNodeID);
		nodeProp.put("高さ", tempFloor);
		nodeProp.put("接続リンクID1", tempLink1ID);
		nodeProp.put("接続リンクID2", tempLink2ID);
		mTempNode.put("_id", tempNodeID);
		mTempNode.put("type", "Feature");
		mTempNode.put("geometry", geometry);
		mTempNode.put("properties", nodeProp);
		// System.out.println(tempNode.toString(4));

		// Create temp links
		mTempLink1 = new JSONObject(link.toString());
		mTempLink2 = new JSONObject(link.toString());
		mTempLink1.put("_id", tempLink1ID);
		mTempLink2.put("_id", tempLink2ID);
		final JSONObject link1Geo = mTempLink1.getJSONObject("geometry"),
				link2Geo = mTempLink2.getJSONObject("geometry");
		JSONArray link1Coords = link1Geo.getJSONArray("coordinates");
		JSONArray link2Coords = link2Geo.getJSONArray("coordinates");
		for (int i = 0; i < linkCoords.length(); i++) {
			if (i > lineSeg) {
				link1Coords.remove(link1Coords.length() - 1);
			} else {
				link2Coords.remove(0);
			}
		}
		JSONArray link1Last = link1Coords.getJSONArray(link1Coords.length() - 1);
		if (!link1Last.equals(nodeCoord)) {
			link1Coords.add(nodeCoord);
		}
		JSONArray link2first = link2Coords.getJSONArray(0);
		if (!link2first.equals(nodeCoord)) {
			link2Coords.add(0, nodeCoord);
		}

		final JSONObject link1Prop = mTempLink1.getJSONObject("properties"),
				link2Prop = mTempLink2.getJSONObject("properties");
		link1Prop.put("リンクID", tempLink1ID);
		link1Prop.put("終点ノードID", tempNodeID);
		link2Prop.put("リンクID", tempLink2ID);
		link2Prop.put("起点ノードID", tempNodeID);
		setLength(mTempLink1);
		setLength(mTempLink2);
		return tempNodeID;
	}

	private static void setLength(JSONObject link) throws Exception {
		JSONArray linkCoords = link.getJSONObject("geometry").getJSONArray("coordinates");
		double length = 0;
		for (int i = 0; i < linkCoords.length() - 1; i++) {
			JSONArray from = linkCoords.getJSONArray(i);
			JSONArray to = linkCoords.getJSONArray(i + 1);
			length += calcDistance(new double[] { from.getDouble(0), from.getDouble(1) },
					new double[] { to.getDouble(0), to.getDouble(1) });
		}
		link.getJSONObject("properties").put("リンク延長", Double.toString(length));
	}

	private static Object getOrthoCenter(JSONArray line, JSONArray point, JSONObject linkProp) throws Exception {
		Point2D.Double c = new Point2D.Double(point.getDouble(0), point.getDouble(1));
		int[] seg = new int[] { 0 };
		calc2Ddistance(line, c, seg);
		JSONArray start = line.getJSONArray(seg[0]);
		JSONArray end = line.getJSONArray(seg[0] + 1);
		Point2D.Double a = new Point2D.Double(start.getDouble(0), start.getDouble(1));
		Point2D.Double b = new Point2D.Double(end.getDouble(0), end.getDouble(1));

		double distCA = Point2D.distance(a.x, a.y, c.x, c.y);
		double distCB = Point2D.distance(b.x, b.y, c.x, c.y);
		double distCX = Line2D.ptSegDist(a.x, a.y, b.x, b.y, c.x, c.y);

		if (distCA <= distCX && seg[0] == 0) {
			return linkProp.getString("起点ノードID");
		} else if (distCB <= distCX && seg[0] == line.length() - 2) {
			return linkProp.getString("終点ノードID");
		} else {
			double distAB = Point2D.distance(a.x, a.y, b.x, b.y);
			double distAX = Math.sqrt(distCA * distCA - distCX * distCX);
			double timeAX = Math.max(0, Math.min(distAX / distAB, 1));

			double x = (b.x - a.x) * timeAX + a.x;
			double y = (b.y - a.y) * timeAX + a.y;
			return new JSONObject().put("x", x).put("y", y).put("seg", seg[0]);
		}
	}

	static double[] CURRENT_POINT = { 139.77392703294754, 35.68662700502585 };
	static double MAX_DISTANCE = 500;

	public static void main(String[] args) {
		try {
			RouteSearchBean search = new RouteSearchBean();
			search.init(CURRENT_POINT, MAX_DISTANCE, "en", false);
			String from = "latlng:" + CURRENT_POINT[0] + ":" + CURRENT_POINT[1];
			JSONArray landmarks = search.getLandmarks();
			JSONObject toNode = landmarks.getJSONObject((int) ((Math.random() / 2 + 0.25) * landmarks.length()));
			String to = toNode.getString("node");
			Object direction = search.getDirection(from, to, new HashMap<String, String>());
			System.out.println(direction.toString());
			System.out.println(from + " to " + toNode.getString("name"));
		} catch (JSONException e) {
			e.printStackTrace();
		}
	}
}
