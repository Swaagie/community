###
Copyright (c) 2002-2011 "Neo Technology,"
Network Engine for Objects in Lund AB [http://neotechnology.com]

This file is part of Neo4j.

Neo4j is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
###

define(
  ['./base',
   'ribcage/View',
   'lib/backbone'], 
  (template, View) ->
  
    class BaseView extends View
      
      template : template
      
      init : (@appState) =>
        $("body").append(@el)
        @appState.bind 'change:mainView', @mainViewChanged

      mainViewChanged : (event) =>
        if @mainView?
          @mainView.detach()
        @mainView = event.attributes.mainView
        @render()

      render : ->
        $(@el).html @template( mainmenu : [ 
          { label : "Cypher",   subtitle:"Data querying",url : "#", current: location.hash is "" }
          { label : "Visualization",subtitle:"Explore the dataset",url : "#/data/" ,    current: location.hash.indexOf("#/data/") is 0 }] )

        if @mainView?
          @mainView.attach($("#contents"))
          @mainView.render()
        return this

      remove : =>
        @appState.unbind 'change:mainView', @mainViewChanged
        if @mainView?
            @mainView.remove()
        super()
)
