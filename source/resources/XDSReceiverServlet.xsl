<?xml version="1.0" encoding="iso-8859-1"?>
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0">
<xsl:output method="xml" encoding="utf-8" omit-xml-declaration="yes" />

<xsl:param name="email"/>
<xsl:param name="dateofbirth"/>
<xsl:param name="accesscode"/>
<xsl:param name="key"/>
<xsl:param name="message"/>
<xsl:param name="home">no</xsl:param>

<xsl:template match="/Studies">
	<html>
		<head>
			<link rel="Stylesheet" type="text/css" media="all" href="/XDSServlet.css"></link>
			<title>Retrieve Studies</title>
		</head>
		<body>
			<xsl:call-template name="home"/>
			<h1>Retrieve Studies from the RSNA Image Share</h1>
			
			<xsl:if test="$message">
				<h2><xsl:value-of select="$message"/></h2>
			</xsl:if>

			<div class="center">
			<form id="formID" action="" method="POST" accept-charset="UTF-8" >

				<center>
				<p class="center">
					<table border="0">
						<tr>
							<td>
								<table border="1">
									<!--
									<tr>
										<td>Email:</td>
										<td><input name="email" type="text" value="{$email}"/></td>
									</tr>
									-->
									<tr>
										<td title="Date of Birth">DOB (YYYYMMDD):</td>
										<td><input name="dateofbirth" type="text" value="{$dateofbirth}"/></td>
									</tr>
									<tr>
										<td>Access Code:</td>
										<td><input name="accesscode" type="text" value="{$accesscode}"/></td>
									</tr>
								</table>
							</td>
							<td>
								<input type="submit" class="button" value="Get Exam List"/>
							</td>
						</tr>
					</table>
				</p>
				</center>

				<xsl:if test="Study">
					<hr/>
					<center>
					<p class="center">
						<table border="0">
							<tr>
								<td>
									<table border="1">
										<xsl:call-template name="StudyHeadings"/>
										<xsl:for-each select="Study">
											<xsl:sort select="@studyDate"/>
											<tr>
												<td class="center"><input type="checkbox" name="study" value="{@hash}"/></td>
												<td><xsl:value-of select="@patientName"/></td>
												<td title="UID: {@studyUID}"><xsl:value-of select="@studyDate"/></td>
												<td>
													<xsl:variable name="sd" select="normalize-space(@studyDescription)"/>
													<xsl:if test="$sd"><xsl:value-of select="$sd"/></xsl:if>
													<xsl:if test="not($sd)">&#160;</xsl:if>
												</td>
											</tr>
										</xsl:for-each>
									</table>
								</td>
								<td>
									<input type="submit" class="button" value="Get Exams"/>
								</td>
							</tr>
						</table>
					</p>
					</center>
				</xsl:if>

			</form>
			</div>
		</body>
	</html>
</xsl:template>

<xsl:template name="StudyHeadings">
	<tr>
		<th>Select</th>
		<th>Patient Name</th>
		<th>Study Date</th>
		<th>Study Description</th>
	</tr>
</xsl:template>

<xsl:template name="home">
	<xsl:if test="$home='yes'">
		<div style="float:right;">
			<img src="/icons/home.png"
				 onclick="window.open('/','_self');"
				 title="Home"
				 style="margin:2"/>
		</div>
	</xsl:if>
</xsl:template>

</xsl:stylesheet>