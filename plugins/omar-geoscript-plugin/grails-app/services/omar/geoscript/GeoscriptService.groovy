package omar.geoscript

import geoscript.filter.Function
import geoscript.geom.GeometryCollection
import geoscript.layer.io.CsvWriter
import geoscript.workspace.Workspace
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.StreamingMarkupBuilder
import org.geotools.data.DataStoreFinder
import org.geotools.factory.CommonFactoryFinder
import org.opengis.filter.capability.FunctionName
import org.springframework.beans.factory.InitializingBean

import grails.transaction.Transactional

@Transactional( readOnly = true )
class GeoscriptService implements InitializingBean
{
  def grailsLinkGenerator

  def parseOptions(def wfsParams)
  {
    def wfsParamNames = [
        'maxFeatures', 'startIndex', 'propertyName', 'sortBy', 'filter'
    ]

    def options = wfsParamNames.inject( [:] ) { options, wfsParamName ->
      if ( wfsParams[wfsParamName] != null )
      {
        switch ( wfsParamName )
        {
        case 'maxFeatures':
          options['max'] = wfsParams[wfsParamName]
          break
        case 'startIndex':
          options['start'] = wfsParams[wfsParamName]
          break
        case 'propertyName':
          def fields = wfsParams[wfsParamName]?.split( ',' )?.collect {
            it.split( ':' )?.last()
          } as List<String>
          if ( fields && !fields?.isEmpty() && fields?.every { it } )
          {
            // println "FIELDS: ${fields.size()}"
            options['fields'] = fields
          }
          break
        case 'sortBy':
          if ( wfsParams[wfsParamName]?.trim() )
          {
            options['sort'] = wfsParams[wfsParamName].split( ',' )?.collect {
              def props = [it] as List
              if ( it.contains( " " ) )
              {
                props = it.split( ' ' ) as List
              }
              else if ( it.contains( "+" ) )
              {
                props = it.split( "\\+" ) as List
              }

              if ( props?.size() == 2 )
              {
                props[1] = ( props[1].equalsIgnoreCase( 'D' ) ) ? 'DESC' : 'ASC'
              }
              props
            }
          }
          break
        default:
          if ( wfsParams[wfsParamName] )
          {
            options[wfsParamName] = wfsParams[wfsParamName]
          }
        }
      }
      options
    }

    options
  }

  def findLayerInfo(def wfsParams)
  {
    def x = wfsParams?.typeName?.split( ':' )
    def namespacePrefix
    def layerName

    switch ( x?.size() )
    {
    case 1:
      layerName = x?.last()
      break
    case 2:
      (namespacePrefix, layerName) = x
      break
    }

    def namespaceInfo

    if ( !namespacePrefix && wfsParams?.namespace )
    {
      def pattern = /xmlns\(\w+=(.*)\)/
      def matcher = wfsParams?.namespace =~ pattern

      if ( matcher )
      {
        def uri = matcher[0][1]

        namespaceInfo = NamespaceInfo.findByUri( uri )
      }
      else
      {
        println "${'*' * 20} No Match ${'*' * 20}"
      }

      layerName = wfsParams?.typeName?.split( ':' )?.last()
    }
    else
    {
      namespaceInfo = NamespaceInfo.findByPrefix( namespacePrefix )
    }

    //println "${namespaceInfo} ${layerName}"

    LayerInfo.where {
      name == layerName && workspaceInfo.namespaceInfo == namespaceInfo
    }.get()
  }

  private def getWorkspaceAndLayer(String layerName)
  {
    def layerInfo = findLayerInfo( [typeName: layerName] )
    def workspace = getWorkspace( layerInfo?.workspaceInfo?.workspaceParams )
    def layer = workspace[layerInfo?.name]

    [workspace, layer]
  }

  def listFunctions2()
  {
    List names = []
    CommonFactoryFinder.getFunctionFactories().each { f ->
      f.functionNames.each { fn ->
        if ( fn instanceof FunctionName )
        {
          names << [name: fn.functionName.toString(), argCount: fn.argumentCount]
        }
      }
    }
    names.sort { a, b -> a.name.compareToIgnoreCase b.name }
  }

  @Override
  void afterPropertiesSet() throws Exception
  {
    Function.registerFunction( "queryCollection" ) { String layerName, String attributeName, String filter ->
      def (workspace, layer) = getWorkspaceAndLayer( layerName )
      def results = layer?.collectFromFeature( filter ) { it[attributeName] }
      workspace?.close()
      results
    }

    Function.registerFunction( 'collectGeometries' ) { def geometries ->
      def multiType = ( geometries ) ? "geoscript.geom.Multi${geometries[0].class.simpleName}" : new GeometryCollection( geometries )

      Class.forName( multiType ).newInstance( geometries )
    }
  }

  Workspace getWorkspace(Map params)
  {
    def dataStore = DataStoreFinder.getDataStore( params )

    ( dataStore ) ? new Workspace( dataStore ) : null
  }

  def getSchemaInfoByTypeName(String typeName)
  {
    def (prefix, layerName) = typeName?.split( ':' )

    def layerInfo = LayerInfo.where {
      name == layerName && workspaceInfo.namespaceInfo.prefix == prefix
    }.get()

    def workspaceInfo = layerInfo.workspaceInfo
    def namespaceInfo = workspaceInfo.namespaceInfo

    def schemaInfo = [
        name: layerName,
        namespace: [prefix: namespaceInfo.prefix, uri: namespaceInfo.uri],
        schemaLocation: grailsLinkGenerator.serverBaseURL
    ]

    Workspace.withWorkspace( getWorkspace( workspaceInfo?.workspaceParams ) ) { workspace ->
      def layer = workspace[layerName]
      def schema = layer.schema

      schemaInfo.attributes = layer.schema.fields.collect { field ->
        def descr = schema.featureType.getDescriptor( field.name )
        [
            maxOccurs: descr.maxOccurs,
            minOccurs: descr.minOccurs,
            name: field.name,
            nillable: descr.nillable,
            type: field.typ
        ]
      }
    }

    schemaInfo
  }

  def getCapabilitiesData()
  {
    [
        featureTypes: getLayerData(),
        functionNames: listFunctions2(),
        featureTypeNamespacesByPrefix: NamespaceInfo.list().inject( [:] ) { a, b ->
          a[b.prefix] = b.uri; a
        }

    ]
  }


  def getLayerData()
  {
    LayerInfo.list()?.collect { layerInfo ->
      def layerData
      WorkspaceInfo workspaceInfo = WorkspaceInfo.findByName( layerInfo.workspaceInfo.name )
      Workspace.withWorkspace( getWorkspace( workspaceInfo?.workspaceParams ) ) { Workspace workspace ->
        def layer = workspace[layerInfo.name]
        def uri = layer?.schema?.uri
        def prefix = NamespaceInfo.findByUri( uri )?.prefix
        def geoBounds

        if ( layer.count() > 0 )
        {
          geoBounds = ( layer?.proj?.epsg == 4326 ) ? layer?.bounds : layer?.bounds?.reproject( 'epsg:4326' )
        }
        else
        {
          geoBounds = [minX: -180.0, minY: -90.0, maxX: 180.0, maxY: 90.0]
        }

        layerData = [
            name: layerInfo.name,
            namespace: [prefix: prefix, uri: uri],
            title: layerInfo.title,
            description: layerInfo.description,
            keywords: layerInfo.keywords,
            proj: layer.proj.id,
            geoBounds: [minX: geoBounds.minX, minY: geoBounds.minY, maxX: geoBounds.maxX, maxY: geoBounds.maxY,]
        ]

      }

      layerData
    }
  }


  def getFeatureCsv(def wfsParams)
  {
    def layerInfo = findLayerInfo( wfsParams )
    def result

    def options = parseOptions( wfsParams )

    def writer = new CsvWriter()
    Workspace.withWorkspace( getWorkspace( layerInfo.workspaceInfo.workspaceParams ) ) {
      workspace ->
        def layer = workspace[layerInfo.name]
        result = writer.write( layer.filter( wfsParams.filter ) )

        workspace.close()
    }


    result
  }

  def getFeatureGML3(def wfsParams)
  {
    def layerInfo = findLayerInfo( wfsParams )
    def xml

    def options = parseOptions( wfsParams )
    def workspaceParams = layerInfo?.workspaceInfo?.workspaceParams

    //println "workspaceParams: ${workspaceParams}"

    def x = {

      Workspace.withWorkspace( getWorkspace( workspaceParams ) ) {
        workspace ->
          def layer = workspace[layerInfo.name]
          def matched = layer?.count( wfsParams.filter ?: Filter.PASS )
          def count = ( wfsParams.maxFeatures ) ? Math.min( matched, wfsParams.maxFeatures ) : matched
          def namespaceInfo = layerInfo?.workspaceInfo?.namespaceInfo

          def schemaLocations = [
              namespaceInfo.uri,
              grailsLinkGenerator.link( absolute: true, uri: '/wfs', params: [
                  service: 'WFS',
                  version: wfsParams.version,
                  request: 'DescribeFeatureType',
                  typeName: wfsParams.typeName
              ] ),
              "http://www.opengis.net/wfs",
              grailsLinkGenerator.link( absolute: true, uri: '/schemas/wfs/1.1.0/wfs.xsd' )
          ]

          mkp.xmlDeclaration()
          mkp.declareNamespace( ogcNamespacesByPrefix )
          mkp.declareNamespace( "${namespaceInfo.prefix}": namespaceInfo.uri )

          wfs.FeatureCollection(
              numberOfFeatures: count,
              timeStamp: new Date().format( "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone( 'GMT' ) ),
              'xsi:schemaLocation': schemaLocations.join( ' ' ),
              numberMatched: matched,
              startIndex: wfsParams.startIndex ?: '0'
          ) {
            if ( !( wfsParams?.resultType?.toLowerCase() == 'hits' ) )
            {
              def features = layer?.getFeatures( options )

              gml.featureMembers {
                features?.each { feature ->
                  mkp.yieldUnescaped(
                      feature.getGml( version: 3, format: false, bounds: false, xmldecl: false, nsprefix: namespaceInfo.prefix )
                  )
                }
              }
            }
          }
      }
    }

    xml = new StreamingMarkupBuilder( encoding: 'utf-8' ).bind( x )

    return xml.toString()
  }

  def getFeatureJSON(def wfsParams)
  {
    def layerInfo = findLayerInfo( wfsParams )
    def results

    def options = parseOptions( wfsParams )

    Workspace.withWorkspace( getWorkspace( layerInfo.workspaceInfo.workspaceParams ) ) {
      workspace ->
        def layer = workspace[layerInfo.name]
        def count = layer.count( wfsParams.filter ?: Filter.PASS )

        def features = ( wfsParams.resultType != 'hits' ) ? layer.collectFromFeature( options ) { feature ->
          return new JsonSlurper().parseText( feature.geoJSON )
        } : []

        results = [
            crs: [
                properties: [
                    name: "urn:ogc:def:crs:${layer.proj.id}"
                ],
                type: "name"
            ],
            features: features,
            totalFeatures: count,
            type: "FeatureCollection"
        ]

        workspace.close()
    }


    return JsonOutput.toJson( results )
  }

  def getFeatureKML(def wfsParams)
  {
    def layerInfo = findLayerInfo( wfsParams )
    def result

    def options = parseOptions( wfsParams )

    Workspace.withWorkspace( getWorkspace( layerInfo.workspaceInfo.workspaceParams ) ) {
      workspace ->
        def layer = workspace[layerInfo.name]
        def features = layer.getFeatures( options )
        result = kmlService.getFeaturesKml( features, [:] )

        workspace.close()
    }


    result
  }
}
