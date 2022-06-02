<%@page import="org.apache.wink.json4j.JSONArray"%>
<%@page import="org.apache.wink.json4j.JSONObject"%>
<jsp:useBean id="authBean" scope="request"
class="hulop.hokoukukan.bean.AuthBean" />
<%@ page language="java" contentType="text/html; charset=UTF-8"
pageEncoding="UTF-8"%>
<%
if (!authBean.supportRole("admin")) {
    response.sendError(HttpServletResponse.SC_FORBIDDEN);
    return;
}
Object profile = authBean.getProfile(request);
if (profile == null || !authBean.hasRole(request, "admin")) {
    response.sendRedirect("login.jsp?logout=true&redirect_url=floorplans.jsp");
    return;
}
String user =  ((JSONObject) profile).getString("user");
%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html>
<head>
<meta name="copyright" content="Copyright (c) IBM Corporation and others 2014, 2022. This page is made available under MIT license.">
<meta charset="UTF-8">
<title>Floor Plans</title>

<script type="text/javascript"
	src="jquery/jquery-1.11.3.min.js"></script>
<link rel="stylesheet" type="text/css"
	href="js/lib/jquery-ui-1.11.4/jquery-ui.min.css">
<script type="text/javascript"
	src="js/lib/jquery-ui-1.11.4/jquery-ui.min.js"></script>
<link rel="stylesheet" type="text/css"
	href="js/lib/DataTables-1.10.10/media/css/jquery.dataTables.css">
<script type="text/javascript"
	src="js/lib/DataTables-1.10.10/media/js/jquery.dataTables.min.js"></script>
<link rel="stylesheet"
        href="openlayers/v4.6.5/ol.css">
<script type="text/javascript"
        src="openlayers/v4.6.5/ol.js"></script>

<link rel="stylesheet" type="text/css" href="css/floorplans.css">
<script type="text/javascript" src="js/datautils.js"></script>
<script type="text/javascript" src="js/mapview.js"></script>
<script type="text/javascript" src="js/floorplan_editor.js"></script>
<script type="text/javascript" src="js/overlay.js"></script>
<script type="text/javascript" src="js/floorplans.js"></script>
<script type="text/javascript" src="js/util.js"></script>
<script type="text/javascript" src="js/commons.js"></script>
<script type="text/javascript" src="js/hokoukukan.js"></script>
</head>
<body>
  <a href="admin.jsp">Admin page</a><br>
  
  <div id='message' style='color: red;'></div>
  
  <br>
  
  <div class="floorplan_hide_edit ref_hide_edit">
    <div style="margin-top: -10px; margin-bottom: 10px;">
      <button id="floorplanAdd_button" onclick="showFloorplanForm()">Add
	a floorplan</button>
      <!--button id="reset_filter" onclick="resetFilter()">Reset
	  filter</button-->
      
      <button id="editSelectedFloorplans" onclick="editSelectedFloorplans()">Edit selected floorplans</button>
      <button id="exportFloorplans" onclick="exportFloorplans('floormaps.zip')">Export for MapServer</button>
      <input type="text" id="findBeacon" placeholder="major-minor"></input>
      <button id="findBeaconBtn" onclick="findBeacon()">Find Beacon</button>
      <button id="findDupBeacons" onclick="findDupBeacons()">Find Duplicated Beacons</button>
      <br>
      <div class="fileUpload btn btn-primary">
	<span>Load route GeoJSON</span>
	<input type="file" id="load_route_geojson_file" class="upload" onchange="loadRouteGeoJSON(this.files[0])"/>
      </div>
      <label class="routeTool"><input type="number" id="default_width" value="5" style="width: 3em">Default Link Width (meters)</label>
      <div id="findBeaconResult"></div>
    </div>
    <div class="ui-widget-content" id="data_table"></div>
  </div>
  
  <div id="floorplan_form" class="floorplan_show_edit" style="display: none"
       title="Floor Plan">
    <form onsubmit="createFloorplan(this); return false;"
	  onreset="hideFloorplanForm()">
      <input type="hidden" id="floorplan_id" name="floorplan_id" value="" />
			<p class="forCreate forEdit forImage forTile">
			  <label for="name">Name:</label><br /> <input id="name" name="name"
								       type="text" size="40" />
			</p>
      <p class="forCreate forEdit forImage forTile">
	<label for="comment">Comment:</label><br />
	<textarea id="comment" name="comment" cols="40" rows="5"></textarea>
      </p>
      <p class="forCreate forImage forTile">
	<input id="is_tile" name="is_tile" type="checkbox" />
	<label for="is_tile">Is this floorplan provided by tile server? </label>
      </p>
      <p class="forCreate forEdit forTile">
	<label for="tile_url">Tile URL:</label><input id="tile_url"
						      name="tile_url" type="text" /><br /> 
      </p>	
      <p class="forCreate forEdit forImage">
	<input id='file' name="file" type="file" />
	<input id='filename' name='filename' type="hidden" />
      </p>
      <p class="forCreate forEdit forImage">
	<label>Type of image:<select id="type" name="type">
	    <option value="floormap" selected>Floor Map</option>
	    <option value="systemmap">System Map</option>
	    <option value="integrated">Integrated System Map</option>
	    <option value="">Others</option>
	</select></label>
      </p>
      <p class="forCreate forEdit forImage forTile forGroup">
	<label for="group">Group Name:</label><input id="group"
						     name="group" type="text" />
      </p>
      <p class="forCreate forEdit forImage forTile">
	<label for="floor">Floor:</label><input id="floor"
						name="floor" type="number" />
      </p>
      <p class="forCreate forEdit forImage forGroup">
	<label for="origin_x">Origin X:</label> <input id="origin_x"
						       name="origin_x" type="text" /><br /> 
	<label for="origin_y">Origin Y:</label> <input id="origin_y"
						       name="origin_y" type="text" />
      </p>
      <p class="forCreate forEdit forImage forGroup">
	<label for="ppm_x">PPM X:</label><input id="ppm_x"
						name="ppm_x" type="text" /><br /> 
	<label for="ppm_y">PPM Y:</label><input id="ppm_y"
						name="ppm_y" type="text" />
      </p>			
      <p class="forCreate forEdit forImage forTile forGroup">
	<label for="lat">Anchor Latitude:</label><input id="lat"
							name="lat" type="text" /><br /> 
	<label for="lng">Anchor Longitude:</label><input id="lng"
							 name="lng" type="text" /><br /> 
      </p>
      <p class="forCreate forEdit forImage forGroup">
	<label for="rotate">Anchor Rotate:</label><input id="rotate"
							 name="rotate" type="text" />
      </p>
      <p class="forCreate forEdit forImage">
	<label for="z-index">z-index:</label><input id="z-index"
						    name="z-index" type="text" />
      </p>
      <p class="forCreate forEdit forTile">
	<label for="coverage">Anchor Coverage:</label><input id="coverage"
							     name="coverage" type="text" />
      </p>
      <p>
	<input type="submit" /> <input type="reset" value="Cancel" />
      </p>
    </form>
  </div>
  
  
  <div class="" id="floorplan_div">
    <h1 class="ui-widget-header">Floor Plan</h1>
    <div id="menu"></div>
    <div id="mapdiv"
	 style="min-width: 960px; min-height: 540px; height: 98vh; height: calc(100vh - 16px); border: 1px solid black; position: relative;"></div>
  </div>
  
  
  
  <div class="">
    <h1 class="ui-widget-header">Anchor</h1>
    <div id="menu2">
      Latitude:<input type="number" id="latitude" step="0.000001" autocomplete="off"></input>
      Longitude:<input type="number" id="longitude" step="0.000001" autocomplete="off"></input>
      Rotate:<input type="number" id="anchor_rotate" step="0.1" min="-180" max="180" autocomplete="off"></input>
      Opacity:<input type="number" id="opacity" step="0.05" min="0" max="1" value="0.8" autocomplete="off"></input>
      <select multiple id="overlays"></select>
      <button id="save">Save</button>
    </div>
    <div id="mapdiv2"
	 style="min-width: 960px; min-height: 540px; height: 98vh; height: calc(100vh - 16px); border: 1px solid black; position: relative;"></div>
  </div>
  
  
  
</body>
</html>
