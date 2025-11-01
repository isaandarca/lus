package es.ico.prestamos.entidad.operacion.demora.demorasobredemora;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import es.ico.prestamos.entidad.cobros.enums.AplicacionCobrosEnum;
import es.ico.prestamos.entidad.cobros.interfaces.Cobro;
import es.ico.prestamos.entidad.cuadroeventos.EventosOperacion;
import es.ico.prestamos.entidad.evento.EventoInfoImp;
import es.ico.prestamos.entidad.evento.interfaces.Evento;
import es.ico.prestamos.entidad.evento.interfaces.EventoAutomatico;
import es.ico.prestamos.entidad.evento.interfaces.EventoInfo;
import es.ico.prestamos.entidad.evento.planevento.PlanEventoImp;
import es.ico.prestamos.entidad.evento.planevento.exceptions.PlanEventoException;
import es.ico.prestamos.entidad.intereses.ImporteImp;
import es.ico.prestamos.entidad.intereses.interfaces.Importe;
import es.ico.prestamos.entidad.operacion.demora.interfaces.PlanDemora;
import es.ico.prestamos.entidad.operacion.demoras.LiqDemorasImp;
import es.ico.prestamos.entidad.operacion.demoras.interfaces.LiqDemoras;
import es.ico.prestamos.entidad.operacion.interes.interfaces.PlanInteresPorEvento;
import es.ico.prestamos.entidad.operacion.operaciones.interfaces.Operacion;
import es.ico.prestamos.entidad.operacion.operaciones.interfaces.OperacionFD;
import es.ico.prestamos.entidad.operacion.operaciones.interfaces.OperacionVPO;
import es.ico.prestamos.entidad.saldos.interfaces.SaldosTotalesOp;
import es.ico.prestamos.entidad.tablasgenerales.enums.ConceptoLiquidacionDemoraEnum;
import es.ico.prestamos.entidad.tablasgenerales.enums.EnumTipoSaldos;
import es.ico.prestamos.entidad.tablasgenerales.enums.PeriodicidadEnum;
import es.ico.prestamos.entidad.tipointeresfijado.comparators.TipoInteresFijadoFechaInicioComparator;
import es.ico.prestamos.entidad.tipointeresfijado.interfaces.TipoInteresFijado;
import es.ico.prestamos.entidad.utils.CantidadTramo;
import es.ico.prestamos.entidad.utils.CantidadTramoDemora;
import es.ico.prestamos.excepciones.POJOValidationException;
import es.ico.prestamos.excepciones.POJOValidationMessage;
import es.ico.prestamos.utils.FechaUtils;

import static es.ico.prestamos.arquitectura.log.LogSingleton.LOG;

public class UtilsCalculoDemoraSobreDemora  {


	/**
	 * Método que ejecuta las demoras sobre demoras, a partir de las demoras
	 * recibidas como parámetro.
	 */
	public static List<EventoAutomatico> calcularDemoraSobreDemora(PlanDemora planDemora,
			Date fechaInicioEjecucionEventos, List<EventoAutomatico> demoras,
			SaldosTotalesOp saldos, EventosOperacion eventosOperacion, List<Date> festivos, List<Date> calendarioDemoras, Map<Date, List<Cobro>> cobros) throws PlanEventoException {
		
		System.out.println("=== INICIO calcularDemoraSobreDemora ===");
		System.out.println("demoras.size: " + (demoras != null ? demoras.size() : "null"));
		for (EventoAutomatico dem : demoras) {
		    System.out.println("Demora: " + dem.getFechaInicio() + " -> " + dem.getFechaEvento());
		}

		List<EventoAutomatico> demorasSobreDemoras = new ArrayList<>();

		PlanInteresPorEvento pie = planDemora.getPlanInteresPorDefectoVigente();
		Set<TipoInteresFijado> tipos = pie.getTipoInteres();

		//INI ICO-62994
		List<CantidadTramo> saldoDemorasAux = null;
		List<CantidadTramo> tramosAcumulados = new ArrayList<>(); 
		Boolean sumarLiquidacion = false; 
		List<Cobro> listaCobrosTratados = new ArrayList<>();
		BigDecimal importeAcumuladoAnt = BigDecimal.ZERO;
		BigDecimal importeCobro = BigDecimal.ZERO;
		Boolean actualizaAcumulado = false;
		Boolean hayTramoNormal = false;
		CantidadTramoDemora tramoAnt = null;
		Boolean usaTramoAnt = false;
		
		Calendar hoy = Calendar.getInstance();
		hoy.set(Calendar.HOUR_OF_DAY, 0);
		hoy.set(Calendar.MINUTE, 0);
		hoy.set(Calendar.SECOND, 0);
		hoy.set(Calendar.MILLISECOND, 0);
		//FIN ICO-62994
		
		/**
		 * Antes de realizar los cálculos las fechas de eventos son ajustadas
		 * por convención de día hábil, en el caso de que sea ajustada la
		 * operacion.
		 */

		PeriodicidadEnum periodicidad = null;
		//obtiene las fechas de vencimiento de demora
		if (calendarioDemoras!=null && !calendarioDemoras.isEmpty()) {
			periodicidad = planDemora.getPlanInteresPorDefectoVigente().getPeriodicidadLiquidacion();

			periodicidad.aplicarPeriodicidad(calendarioDemoras.get(calendarioDemoras.size()-1));
			
			ArrayList<Date> fechasCobros = new ArrayList<Date>(); //ICO-37649
			ArrayList<Cobro> listaCobros= new ArrayList<Cobro>();
			List<Date> calendarioDemorasAux= new ArrayList<Date>();
			
			if(cobros!=null && cobros.size()>0){ //ICO-37649 (para tener en cuenta los cobros de demora)
	
				Collection<List<Cobro>> collectionCobros = cobros.values();
				
				for (List<Cobro> listCob : collectionCobros){  //Saco las fechas de los cobros a una lista si son de demoras
					
					for(Cobro cobro : listCob){
						
						if(cobro.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_DEMORAS.getCodigo())){
							listaCobros.add(cobro);
							fechasCobros.add(cobro.getFechaCobro());
						}	
					}
				}
			}
			
			if(listaCobros!=null && !listaCobros.isEmpty()){  //Ordeno los cobros por fecha
				Collections.sort(listaCobros, comparadorCobrosPorFecha());
			}
			if(fechasCobros!=null && !fechasCobros.isEmpty()){
				
				calendarioDemorasAux.addAll(calendarioDemoras);
				
				for(Date d: fechasCobros){
					boolean yaexiste=false;
					for(Date c: calendarioDemoras){
						if(!FechaUtils.truncateDate(d).after(c) && !FechaUtils.truncateDate(d).before(c)){
							yaexiste=true;
							break;
						}
					}
					
					if(!yaexiste){
						calendarioDemorasAux.add(d);
					}
				}
			}else{
				calendarioDemorasAux.addAll(calendarioDemoras);
			}
			
			Collections.sort(calendarioDemorasAux,comparadorFechasMenorAMayor());
			
			
			List<CantidadTramoDemora> tramosCalculo = obtenerTramosDeDemora(planDemora,calendarioDemorasAux, tipos);

			BigDecimal importeAcumulado = BigDecimal.ZERO;

			if (!demoras.isEmpty()){


				importeAcumulado = saldos.getSaldoOperacion(fechaInicioEjecucionEventos, EnumTipoSaldos.SALDO_MORA_DEMORA);

				for(Evento liqDemora : demoras) {
					if(!liqDemora.getFechaEvento().after(fechaInicioEjecucionEventos)) {
						continue;
					}
					// INI ICO-62994
					if(planDemora.getOperacion() instanceof OperacionFD && !esCarteraTraspasada(planDemora.getOperacion()))
					{
						if(liqDemora.getFechaVencimientoAjustada() == null) {
							liqDemora.setFechaVencimientoAjustada(getFechaAjustadaCobro(planDemora,liqDemora.getFechaEvento(),festivos).getTime());
						}
					}
					// FIN ICO-62994
					BigDecimal importeASumar = BigDecimal.ZERO;
					
					if(fechaInicioEjecucionEventos.compareTo(liqDemora.getFechaInicio())>=0) {

						//Genero demoras de la fecha inicio del evento a la fecha inicio ejecución
						List<CantidadTramo> saldoDemoras = saldos.getSaldosOperacion(liqDemora.getFechaInicio(), liqDemora.getFechaEvento(),
								EnumTipoSaldos.SALDO_MORA_DEMORA);
						
						//INI ICO-62994
						if((planDemora.getOperacion() instanceof OperacionFD || planDemora.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(planDemora.getOperacion()))
						{
							if(saldoDemoras != null && saldoDemoras.size() > 1) {
								saldoDemorasAux = getSaldosDemorasABorrar(planDemora, saldoDemoras, festivos, hoy);
							}
							
							if(saldoDemorasAux != null && !saldoDemorasAux.isEmpty()) {
								saldoDemoras.removeAll(saldoDemorasAux);
							} 
							
							for(CantidadTramo saldoAux : saldoDemoras) {
								if(!hoy.after(getFechaAjustadaCobro(planDemora,liqDemora.getFechaEvento(),festivos))) {
										sumarLiquidacion = false;
										break;
								} else {
									sumarLiquidacion = true;
								}
								
								if((saldoAux.getfechaIni().compareTo(saldoAux.getfechaFin()) < 0 &&
										saldoAux.getfechaFin().compareTo(liqDemora.getFechaEvento()) == 0)) {
									sumarLiquidacion = true;
									hayTramoNormal = true;
								}
								if((saldoAux.getfechaIni().compareTo(saldoAux.getfechaFin()) == 0 &&
										saldoAux.getfechaIni().compareTo(liqDemora.getFechaEvento()) == 0) &&
										!hayTramoNormal) {
									sumarLiquidacion = false;
								}
							}
							
							if(saldoDemoras.isEmpty()) {
								sumarLiquidacion = true;
							}
						}
						//FIN ICO-62994
						
						for(CantidadTramo saldoDemora : saldoDemoras){
							CantidadTramoDemora tramo = new CantidadTramoDemora(saldoDemora.getfechaIni(), saldoDemora.getfechaFin(), null, null);
							for (TipoInteresFijado tipo : tipos) {
								if ((FechaUtils.convertirFecha(liqDemora.getFechaInicio()).compareTo(FechaUtils.convertirFecha(tipo.getFecha_inicio()))>=0)
										&&(tramo.getfechaFin().compareTo(FechaUtils.sumaUnDiaCalendario(tipo.getFecha_final()))<=0)) {
	
									EventoInfo tipoTramo = new EventoInfoImp();
									if (tipo.esAbsoluto()) {
										tipoTramo.setPorcentajeAplicado(BigDecimal.ZERO);
									} else {
										tipoTramo.setPorcentajeAplicado(tipo.getValor_fijado());
									}
									tipoTramo.setFechaInicioValidez(tipo.getFecha_inicio());
									tipoTramo.setFechaFinValidez(FechaUtils.sumaUnDiaCalendario(tipo.getFecha_final()));
	
									tramo.setTipo(tipoTramo);
									break;
								}
							}
							//INI-ICO-62994
							if((planDemora.getOperacion() instanceof OperacionFD || planDemora.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(planDemora.getOperacion())) {
								if(tramo.getfechaIni().compareTo(tramo.getfechaFin()) == 0 && saldoDemoras.size() > 1 && tramoAnt != null && tramoAnt.getfechaFin().compareTo(tramo.getfechaIni()) == 0) {
									importeAcumulado=saldos.getSaldoOperacion(tramo.getfechaIni(), EnumTipoSaldos.SALDO_MORA_DEMORA).add(tramoAnt.getCantidad());//Solucion recalculo 09-05-2021
									usaTramoAnt = true;
								} else {
									importeAcumulado=saldos.getSaldoOperacion(tramo.getfechaIni(), EnumTipoSaldos.SALDO_MORA_DEMORA);//Problema al recalcular desde 09-05-2021 con acumulado al 19-05-2021
								} 
							} else {//FIN ICO-62994
								importeAcumulado=saldos.getSaldoOperacion(tramo.getfechaIni(), EnumTipoSaldos.SALDO_MORA_DEMORA);
							}
														
							EventoAutomatico demoraSobreDemora = doDemoraSobreDemora(planDemora,tramo, importeAcumulado);
							//INI ICO-62994
							CantidadTramo tramoAux = tramo;
							
							if((planDemora.getOperacion() instanceof OperacionFD || planDemora.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(planDemora.getOperacion())) {
								tramoAux.setCantidad(importeAcumulado);
								
								for(CantidadTramo saldoAux : tramosAcumulados) {
									if(saldoAux.getfechaFin().compareTo(tramoAux.getfechaFin()) == 0) {
										saldoAux.setCantidad(tramoAux.getCantidad());
										actualizaAcumulado = true;
									}
								}
								
								if(!actualizaAcumulado) {
									tramosAcumulados.add(tramo);
								} else {
									actualizaAcumulado = false;
								}
							}
							//FIN ICO-62994
							if(demoraSobreDemora != null) {
								//datos obligatorios
								fillDemoraGenerada(planDemora, demoraSobreDemora, festivos);
								//añadir demora sobre demora al saco de eventos, asociar al plan
								if (demoraSobreDemora.getImporte().getCantidad().compareTo(BigDecimal.ZERO)>0)
									demorasSobreDemoras.add(demoraSobreDemora);
	
								if (demoraSobreDemora.getImporte()!=null&&demoraSobreDemora.getImporte().getCantidad()!=null) {
									importeASumar = importeASumar.add(demoraSobreDemora.getImporte().getCantidad());
								}
							}
							//INI ICO-62994
							if((planDemora.getOperacion() instanceof OperacionFD || planDemora.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(planDemora.getOperacion())) {
								tramoAnt = tramo;
							}
							//FIN ICO-62994
						}
						
						//INI ICO-62994
						if((planDemora.getOperacion() instanceof OperacionFD || planDemora.getOperacion() instanceof OperacionVPO)  && !esCarteraTraspasada(planDemora.getOperacion()))
						{
							if(sumarLiquidacion) {//Hay que revisar los cobros para ver si se modifica el importe acumulado
								importeAcumuladoAnt = importeAcumulado;
								
								importeAcumulado = importeAcumulado.add(importeASumar).add(liqDemora.getImporte().getCantidad());
								
								for(Cobro cobro : listaCobros){
									if(cobro.getFechaCobro().compareTo(liqDemora.getFechaVencimientoAjustada()) == 0) {
										importeCobro = importeCobro.add(cobro.getImporte().getCantidad());
										cobro.getImporte().setCantidad(BigDecimal.ZERO);//Recalculo 08-09-2021
									}
								}
								
								if(importeCobro.compareTo(BigDecimal.ZERO) > 0) {
									if(usaTramoAnt) {
										importeAcumulado = importeAcumulado.subtract(importeCobro);//Solucion recalculo 09-05-2021
									} else {
										importeAcumulado = importeAcumulado.subtract(importeCobro).add(importeAcumuladoAnt);//Recalc. 09-05-2021 Falla sumar importeAcumuladoAnt
									}
									
								}
								
								sumarLiquidacion = false;
							} else {
								importeAcumulado = importeAcumulado.add(importeASumar);
							}
						} else { //FIN ICO-62994
							importeAcumulado = importeAcumulado.add(importeASumar).add(liqDemora.getImporte().getCantidad());
						}
					}
					else {
						List<CantidadTramo> saldoDemoras = saldos.getSaldosOperacion(liqDemora.getFechaInicio(), liqDemora.getFechaEvento(),
								EnumTipoSaldos.SALDO_MORA_DEMORA);
						List<CantidadTramoDemora> tramosDemoraConSaldo = new ArrayList<CantidadTramoDemora>();
						List<CantidadTramoDemora> tramosDemora = filtrarTramosDemoraSobreDemora(tramosCalculo,
								liqDemora.getFechaInicio(), liqDemora.getFechaEvento());

						for (CantidadTramoDemora tramo : tramosDemora) {
							if(tramo.getfechaFin().after(liqDemora.getFechaEvento()))
								break;
							for(CantidadTramo saldoDemora : saldoDemoras) {
								Date fechaSaldo = saldoDemora.getfechaFin();
								if ((fechaSaldo.after(tramo.getfechaIni()))
										&&(fechaSaldo.before(tramo.getfechaFin()))) {
									CantidadTramoDemora nuevoTramo = new CantidadTramoDemora(tramo);
									nuevoTramo.setfechaFin(fechaSaldo);
									nuevoTramo.setfechaIni(FechaUtils.sumaUnDiaCalendario(fechaSaldo));
									nuevoTramo.setCantidad(saldoDemora.getCantidad());
									tramosDemoraConSaldo.add(nuevoTramo);
								}
							}
							tramosDemoraConSaldo.add(tramo);
						}
						tramosDemora = tramosDemoraConSaldo;

						// FIX VPO: Agregado OperacionVPO a la condición para aplicar mismo filtro de fecha que FD
						if(!((planDemora.getOperacion() instanceof OperacionFD || planDemora.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(planDemora.getOperacion())) || hoy.after(getFechaAjustadaCobro(planDemora,liqDemora.getFechaEvento(),festivos))) { //ICO-62994
							importeASumar = liqDemora.getImporte().getCantidad();
						}

						if(tramosDemora.isEmpty()){
							importeAcumulado=importeAcumulado.add(importeASumar);
						}
						
						ArrayList<Cobro> listaCobrosBorrarUno= new ArrayList<Cobro>(); 
						for(CantidadTramoDemora tramo : tramosDemora) {
							for(Cobro cobro : listaCobros){
								//INI-ICO-62994
								if(planDemora.getOperacion() instanceof OperacionFD && !esCarteraTraspasada(planDemora.getOperacion())) {
									if(tramo.getfechaIni().compareTo(cobro.getFechaCobro()) <= 0 &&
											getFechaAjustadaCobro(planDemora,tramo.getfechaIni(),festivos).getTime().compareTo(cobro.getFechaCobro()) >= 0){
										if(importeAcumulado.compareTo(cobro.getImporte().getCantidad()) > 0){
											importeAcumulado=importeAcumulado.subtract(cobro.getImporte().getCantidad());
											cobro.getImporte().setCantidad(BigDecimal.ZERO);
											listaCobrosBorrarUno.add(cobro);
										}
										else {
											importeAcumulado=BigDecimal.ZERO;
											cobro.getImporte().setCantidad(cobro.getImporte().getCantidad().subtract(importeAcumulado));
											listaCobrosBorrarUno.add(cobro);
										}
										
										for(CantidadTramo tramoAux : tramosAcumulados) {
											if(tramoAux.getfechaFin().compareTo(tramo.getfechaIni()) == 0) {
												if(importeCobro.compareTo(BigDecimal.ZERO) == 0 && importeAcumuladoAnt.compareTo(BigDecimal.ZERO) == 0) { //ICO-62994 Recalculo 08-05-2021
													importeAcumulado = importeAcumulado.add(tramoAux.getCantidad());
												}
												importeCobro = BigDecimal.ZERO; //ICO-62994 Recalculo 08-05-2021
												importeAcumuladoAnt = BigDecimal.ZERO; //ICO-62994 Recalculo 08-05-2021
												
												cobro.getImporte().setCantidad(cobro.getImporte().getCantidad().add(tramoAux.getCantidad()));
												listaCobrosBorrarUno.remove(cobro);
												listaCobrosTratados.add(cobro); //ICO-62994 incluir cobro en lista de cobros tratados
											}
										}
									}
								} else {//FIN ICO-62994
									if(tramo.getfechaIni().equals(cobro.getFechaCobro())){
										if(importeAcumulado.compareTo(cobro.getImporte().getCantidad()) > 0){
											importeAcumulado=importeAcumulado.subtract(cobro.getImporte().getCantidad());
											listaCobrosBorrarUno.add(cobro);
										}
										else {
											importeAcumulado=BigDecimal.ZERO;
											listaCobrosBorrarUno.add(cobro);
										}
									}
								}
							}
							
							listaCobros.removeAll(listaCobrosBorrarUno);
							
							EventoAutomatico demoraSobreDemora = doDemoraSobreDemora(planDemora,tramo, importeAcumulado);
							//INI-ICO-62994
							CantidadTramo tramoAux = tramo;
							if(planDemora.getOperacion() instanceof OperacionFD && !esCarteraTraspasada(planDemora.getOperacion())) {
								tramoAux.setCantidad(importeAcumulado);
								
								for(CantidadTramo saldoAux : tramosAcumulados) {
									if(saldoAux.getfechaFin().compareTo(tramoAux.getfechaFin()) == 0) {
										saldoAux.setCantidad(tramoAux.getCantidad());
										actualizaAcumulado = true;
									}
								}
								
								if(!actualizaAcumulado) {
									tramosAcumulados.add(tramo);
								} else {
									actualizaAcumulado = false;
								}
							}
							//FIN ICO-62994
							if(demoraSobreDemora != null) {
								//datos obligatorios
								fillDemoraGenerada(planDemora, demoraSobreDemora, festivos);
								//añadir demora sobre demora al saco de eventos, asociar al plan
								if (demoraSobreDemora.getImporte().getCantidad().compareTo(BigDecimal.ZERO)>0)
									demorasSobreDemoras.add(demoraSobreDemora);

								// FIX VPO: Agregado OperacionVPO a la condición para aplicar mismo filtro de fecha que FD
								if (demoraSobreDemora.getImporte()!=null&&demoraSobreDemora.getImporte().getCantidad()!=null
										&& (!((planDemora.getOperacion() instanceof OperacionFD || planDemora.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(planDemora.getOperacion())) || hoy.after(getFechaAjustadaCobro(planDemora,demoraSobreDemora.getFechaEvento(),festivos)))) { //ICO-62994
									importeASumar = importeASumar.add(demoraSobreDemora.getImporte().getCantidad());
								}
							}
							
							boolean yaResto= false;
							ArrayList<Cobro> listaCobrosBorrarDos= new ArrayList<Cobro>(); 
							for(Cobro cobro : listaCobros){
								if(tramo.getfechaFin().equals(cobro.getFechaCobro())){//Probar a calcular fecha vencimiento ajustada de tramo.getfechaFin()
									//INI ICO-62994
									if(planDemora.getOperacion() instanceof OperacionFD && !esCarteraTraspasada(planDemora.getOperacion())) {
										if(importeCobro.compareTo(BigDecimal.ZERO) > 0) {
											importeAcumulado = importeAcumulado.subtract(importeAcumuladoAnt).add(importeCobro);
											importeCobro = BigDecimal.ZERO;
											importeAcumuladoAnt = BigDecimal.ZERO;
										}
									}
									//FIN ICO-62994
									if((importeAcumulado.add(importeASumar)).compareTo(cobro.getImporte().getCantidad()) > 0){
										//INI ICO-62994
										if(planDemora.getOperacion() instanceof OperacionFD && !esCarteraTraspasada(planDemora.getOperacion())) {
											if(tramo.equals(tramosDemora.get(tramosDemora.size()-1))){//Si es el último tramo que entre
												importeAcumulado = importeAcumulado.add(importeASumar);
											}
										} else {//FIN ICO-62994
											if(tramosDemora.size() == 1) {
												importeAcumulado = importeAcumulado.add(importeASumar);
											}
										}
										
										importeAcumulado=importeAcumulado.subtract(cobro.getImporte().getCantidad());
										listaCobrosBorrarDos.add(cobro);
										yaResto=true;
									}
									else {
										if(tramosDemora.size() == 1){
											importeAcumulado = importeAcumulado.add(importeASumar);
										}
										importeAcumulado=BigDecimal.ZERO;
										listaCobrosBorrarDos.add(cobro);
										yaResto=true;
									}
								}
							}
							if(!yaResto){
								importeAcumulado = importeAcumulado.add(importeASumar);
							}
							
							listaCobros.removeAll(listaCobrosBorrarDos);
						}

						
					}
				}

				if(calendarioDemoras.size() == 1){
					for (TipoInteresFijado tipo : tipos){
						if(FechaUtils.sumarDias(tipo.getFecha_final(), 1).compareTo(calendarioDemoras.get(0))==0){
							calendarioDemoras.add(tipo.getFecha_inicio());
							Collections.sort(calendarioDemoras,comparadorFechasMenorAMayor());
						}
					}
				}
				
				Iterator<Date> iteratorFecha=calendarioDemoras.iterator();

				Date fechaInicio = iteratorFecha.next();
				Date fechaFin=null;
				
				while(iteratorFecha.hasNext()){

					fechaFin=iteratorFecha.next();

					if (!fechaInicioEjecucionEventos.after(fechaFin)) {
						reagruparDemorasSobreDemoras(planDemora, demoras, demorasSobreDemoras,fechaInicio, fechaFin, festivos);
					}

					fechaInicio=fechaFin;
				}

				for(EventoAutomatico demoraActual :demoras){
					LiqDemoras liqDemTotal=(LiqDemoras)demoraActual;

					// ICG. A las demoras, tengan o no más de un evento parcial, se le establece DEMORA como tipo de concepto.
					liqDemTotal.setConcepto(ConceptoLiquidacionDemoraEnum.DEMORA.getCodigo());
				}
			}
		}
	    System.out.println("=== DEMORAS + PARCIALES (por padre) ===");
	    for (EventoAutomatico dem : demoras) {
	        System.out.println("PADRE -> " + dem.getFechaInicio() + " a " + dem.getFechaEvento()
	            + " | parciales=" + (dem.getEventosParciales()==null ? 0 : dem.getEventosParciales().size()));
	        if (dem.getEventosParciales()!=null) {
	            for (EventoAutomatico p : dem.getEventosParciales()) {
	                System.out.println("   parcial fechaEvento=" + p.getFechaEvento()
	                    + " tipo=" + (p.getEventoInfo()!=null ? p.getId() : "?")
	                    + " base=" + (p.getEventoInfo()!=null ? p.getEventoInfo().getImporteBase().getCantidad() : "?"));
	            }
	        }
	    }
		return demorasSobreDemoras;
	}
	
	//INI ICO-62994
	private static Boolean esCarteraTraspasada(Operacion op) {
		Boolean esCarteraTraspasada = false;
		
		if (op instanceof OperacionFD && op.getCodigoHost() != null &&
				(op.getCodigoHost().startsWith("1518") ||
				 op.getCodigoHost().startsWith("1519") ||
				 op.getCodigoHost().startsWith("1520"))) {
			esCarteraTraspasada = true;
		}
		
		return esCarteraTraspasada;
	}
	
	private static List<CantidadTramo> getSaldosDemorasABorrar(PlanDemora planDemora, List<CantidadTramo> saldosDemoras, List<Date> festivos, Calendar hoy){
		List<CantidadTramo> saldosABorrar = new ArrayList<>();
		
		Calendar fechaAuxIniAjustada;
		Calendar fechaAuxFinAjustada;
		
		for(CantidadTramo saldoAux : saldosDemoras) {
			fechaAuxIniAjustada = getFechaAjustadaCobro(planDemora,saldoAux.getfechaIni(),festivos);
			fechaAuxFinAjustada = getFechaAjustadaCobro(planDemora,saldoAux.getfechaFin(),festivos);
			
			if(!hoy.after(fechaAuxFinAjustada) && fechaAuxIniAjustada.compareTo(fechaAuxFinAjustada) == 0) {
				saldosABorrar.add(saldoAux);
			}
		}
		
		return saldosABorrar;
	}
	
	private static Calendar getFechaAjustadaCobro(PlanDemora planDemora,Date fecha,List<Date> festivos) {
		Calendar fechaCobro = Calendar.getInstance();
		
		try {
			fechaCobro.setTime(((PlanEventoImp)planDemora).getFechaParaPago(planDemora.getOperacion().getPlanAjustableDias(), fecha, festivos, planDemora.getClass(), false));
		} 
		catch (PlanEventoException e) {
			LOG.info(UtilsCalculoDemoraSobreDemora.class.getName()+ ".getFechaAjustadaCobro Error al ajustar las fechas ", e);
			fechaCobro.setTime(fecha);
		}
		
		if(planDemora.getOperacion().getPlanAjustableDias().getPeriodoDeGracia() != null) {
			fechaCobro.add(Calendar.DAY_OF_YEAR, planDemora.getOperacion().getPlanAjustableDias().getPeriodoDeGracia());
		}
		
		return fechaCobro;
	}
	// FIN ICO-62994

	/**
	 * Método que calcula la liquidación parcial de demora, en base al
	 * tipo de interes en el tramo dado y al importe acumulado de las
	 * anteriores demoras sobre demoras.
	 *
	 * (importe acumulado) * tipo * base de calculo
	 *
	 * @author salonso
	 * @param tramoDemora
	 * @param importeAcumulado
	 * @param divisa
	 *
	 * @return demoraSobreDemora con el importe calculado.
	 *
	 * @throws PlanEventoException
	 */
	private static EventoAutomatico doDemoraSobreDemora(PlanDemora planDemora, CantidadTramoDemora tramoDemora, BigDecimal importeAcumulado)
			throws PlanEventoException {
		if(tramoDemora.getTipo() == null)
			return null;

		LiqDemoras liqDemora = new LiqDemorasImp();
		liqDemora.setConcepto(ConceptoLiquidacionDemoraEnum.RESTO.getCodigo());
		/*
		 * calcular base calculo
		 */
		BigDecimal baseCalculoConjunta = null;
		try {
			//se obtiene la base de calculo conjunta
			baseCalculoConjunta = planDemora.getPlanInteresPorDefectoVigente().getBaseCalculo().getBaseConjunta(
					tramoDemora.getfechaIni(), tramoDemora.getfechaFin(), null, null);
		} catch (Exception e) {
			POJOValidationMessage m = new POJOValidationMessage(UtilsCalculoDemoraSobreDemora.class.getSimpleName()+e.getCause()
					+" Error al obtener base de cálculo conjunta");
			throw new PlanEventoException(m);
		}

		/*
		 * calcular importe liquidacion.
		 * Si no tiene tipo, la liquidacion es 0.
		 */
		ImporteImp i0 = new ImporteImp();

		try {
			if (tramoDemora.getTipo()!=null) {
				//el importe de la demora sobre demora se calcula
				//(importe del tramo + importe acumulado) * tipo * base de calculo
				BigDecimal importe = planDemora.getPlanInteresPorDefectoVigente().obtenerImporteLiquidacion(importeAcumulado,
						tramoDemora.getTipo().getPorcentajeAplicado(), baseCalculoConjunta);
				i0.setCantidad(importe);
			}
		} catch (Exception e) {
			POJOValidationMessage m = new POJOValidationMessage(UtilsCalculoDemoraSobreDemora.class.getSimpleName()+e.getCause()
					+" Error al hacer el cálculo de la liquidación de demora sobre demora");
			throw new PlanEventoException(m);
		}

		liqDemora.setImporte(i0);
		/*
		 * Info
		 */

		liqDemora.setEventoInfo(new EventoInfoImp());
		liqDemora.setEventoInfo(tramoDemora.getTipo());

		liqDemora.getEventoInfo().getImporteBase().setCantidad(importeAcumulado);
		/*
		 * fecha de evento
		 */
		liqDemora.setFechaEvento(tramoDemora.getfechaFin());
		liqDemora.setFechaInicio(tramoDemora.getfechaIni());

		return liqDemora;

	}

	/**
	 * Método que rellena una demora sobre demora con los datos obligatorios de los eventos.
	 * Ajusta la fecha de vencimiento ajustada si aplica.
	 * Asocia la demora sobre demora al plan evento agregado, es decir, al plan de demora,
	 * para mantener de esta forma todas las demoras asociadas al mismo plan, puesto que las
	 * demoras se van a tratar como demoras de resto.
	 *
	 * @author salonso
	 * @param demoraSobreDemora
	 * @param festivos
	 * @throws PlanEventoException
	 */
	private static void fillDemoraGenerada(PlanDemora planDemora, EventoAutomatico demoraSobreDemora, List<Date> festivos)
			throws PlanEventoException {
	    	planDemora.auditarEventoAutomatico(demoraSobreDemora);
		demoraSobreDemora.setEsEstadoActivo(true);
		demoraSobreDemora.setPlanEvento(planDemora);
		try {
			if(!(planDemora.getOperacion() instanceof OperacionFD)){
		    	planDemora.ajustarFechasVencimiento(demoraSobreDemora, planDemora.getOperacion().getPlanAjustableDias(), festivos);
			}
		} catch (PlanEventoException e) {
			POJOValidationMessage m = new POJOValidationMessage(UtilsCalculoDemoraSobreDemora.class.getSimpleName()+e.getCause()
					+" Error al ajustar fechas de demoras sobre demoras");
			throw new PlanEventoException(m);
		}
	}

	private static EventoAutomatico generarDemoraPadreParaUltimoTramo(PlanDemora planDemora, Date fechaDemora,
			List<Date> festivos) throws PlanEventoException {
		LiqDemoras demoraNew = new LiqDemorasImp();
		demoraNew.setFechaEvento(fechaDemora);
		demoraNew.setConcepto(ConceptoLiquidacionDemoraEnum.DEMORA.getCodigo());
		//INICIALIZAMOS A CERO EURACOS
		demoraNew.setImporte(ImporteImp.getImporteZERO());

		fillDemoraGenerada(planDemora,demoraNew, festivos);

		return demoraNew;
	}

	/**
	 * Método que devuelve una lista de objetos auxiliares, delimitados por
	 * los cambios de tipo de interes en cada periodo del calendario de fechas
	 * de liquidación recibido.
	 *
	 * @author rasensio
	 * @param fechasLiquidacion
	 * @param tiposInteres
	 * @return List<CantidadTramoDemora>
	 */
	private static List<CantidadTramoDemora> obtenerTramosDeDemora(PlanDemora planDemora, List<Date> fechasLiquidacion,
			Set<TipoInteresFijado> tiposInteres) {
		List<CantidadTramoDemora> cantidadesTramo = new ArrayList<CantidadTramoDemora>();
		List<TipoInteresFijado> tiposInteresOrdenados = new ArrayList<TipoInteresFijado>();
		HashSet<Date> fechasTramo = new HashSet<Date>();

		//Se generan las fechas de tramo
		Date fechaInicio = fechasLiquidacion.get(0);
		Date fechaFin = fechasLiquidacion.get(fechasLiquidacion.size()-1);

		tiposInteresOrdenados.addAll(planDemora.getPlanInteresPorDefectoVigente().obtenerTiposTratados(
				tiposInteres));
		Collections.sort(tiposInteresOrdenados, new TipoInteresFijadoFechaInicioComparator());

		//se obtiene una copia del ultimo tipo de interes ya que se modificara la fecha final
		TipoInteresFijado ultimoTipoInteresFijado = (TipoInteresFijado) tiposInteresOrdenados.get(
				tiposInteresOrdenados.size()-1).copyBean();

		//Si la fecha fin del ultimo interes es menor que la ultima fecha de liquidacion se pone como fecha final
		if (ultimoTipoInteresFijado.getFecha_final().before(fechaFin)) {
			fechaFin = ultimoTipoInteresFijado.getFecha_final();
			fechaFin = FechaUtils.sumaUnDiaCalendario(fechaFin);
		}

		//Se añaden las fechas de liquidación a las fecha de tramo si estas
		//no inician despues despues de la fecha de fin y no finalizan antes de la fecha de inicio
		if ((!fechasLiquidacion.get(0).after(fechaFin))
				&&(!fechasLiquidacion.get(fechasLiquidacion.size()-1).before(fechaInicio))) {
			for (Date fechaLiquidacion : fechasLiquidacion) {
				if (!fechaLiquidacion.after(fechaFin)) {
					fechasTramo.add(fechaLiquidacion);
				}
			}
		}

		//Se añaden las fechas de tipos de interes a las fecha de tramo si estas
		//no inician despues despues de la fecha de fin y no finalizan antes de la fecha de inicio
		if ((!tiposInteresOrdenados.get(0).getFecha_inicio().after(fechaFin))
				&&(!ultimoTipoInteresFijado.getFecha_final().before(fechaInicio))) {
			for (TipoInteresFijado tipo : tiposInteresOrdenados) {
				//se añade el tipo si inica dentro de las fechas inicial y final
				if ((!tipo.getFecha_inicio().before(fechaInicio))&&(!tipo.getFecha_inicio().after(fechaFin))) {
					fechasTramo.add(tipo.getFecha_inicio());
					if (!tipo.getFecha_final().after(fechaFin)) {
						fechasTramo.add(FechaUtils.sumaUnDiaCalendario(tipo.getFecha_final()));
					} else {
						fechasTramo.add(fechaFin);
					}
				}
				//se añade el tipo si finaliza dentro de las fechas inicial y final
				else if ((!tipo.getFecha_final().after(fechaFin))&&(!tipo.getFecha_final().before(fechaInicio))) {
					fechasTramo.add(FechaUtils.sumaUnDiaCalendario(tipo.getFecha_final()));
					if (!tipo.getFecha_inicio().before(fechaInicio)) {
						fechasTramo.add(tipo.getFecha_inicio());
					} else {
						fechasTramo.add(fechaInicio);
					}
				}
			}
		}

		//se crean cantidades de demora para cada tramo de fechas
		List<Date> fechasTramoOrdenadas = new ArrayList<Date>(fechasTramo);
		Collections.sort(fechasTramoOrdenadas);
		for (int i = 0; i<fechasTramoOrdenadas.size()-1; i++) {
			Date fechaDesde = fechasTramoOrdenadas.get(i);
			Date fechaHasta = fechasTramoOrdenadas.get(i+1);
			CantidadTramoDemora cantidadTramo = new CantidadTramoDemora(fechaDesde, fechaHasta, null, null);
			//se comprueba si el tramo tiene un importe de tipo
			//esto es cuando un tramo esta dentro de un tramo de tipo
			for (TipoInteresFijado tipo : tiposInteresOrdenados) {
				if ((FechaUtils.convertirFecha(fechaDesde).compareTo(FechaUtils.convertirFecha(tipo.getFecha_inicio()))>=0)
						&&(fechaHasta.compareTo(FechaUtils.sumaUnDiaCalendario(tipo.getFecha_final()))<=0)) {

					EventoInfo tipoTramo = new EventoInfoImp();
					if (tipo.esAbsoluto()) {
						tipoTramo.setPorcentajeAplicado(BigDecimal.ZERO);
					} else {
						tipoTramo.setPorcentajeAplicado(tipo.getValor_fijado());
					}
					tipoTramo.setFechaInicioValidez(tipo.getFecha_inicio());
					tipoTramo.setFechaFinValidez(FechaUtils.sumaUnDiaCalendario(tipo.getFecha_final()));

					cantidadTramo.setTipo(tipoTramo);
					break;
				}
			}
			cantidadesTramo.add(cantidadTramo);
		}

		return cantidadesTramo;
	}

	/**
	 * Metodo que devuelve los tramos de demora comprendidos entre una fecha de inicio y final
	 *
	 * Este metodo tambien elina los tramos tratados y las anteriores
	 * del set de tramos que recibe como parametro para que no sean tratados posteriormente.
	 *
	 * @param tramosCalculo tramos de demora
	 * @param fechaInicio Fecha inicio
	 * @param fechaFin Fecha fin
	 *
	 * @return Fechas de inicio y fin de tramo y fechas con cambio de saldo.
	 */
	private static List<CantidadTramoDemora> filtrarTramosDemoraSobreDemora(List<CantidadTramoDemora> tramosCalculo,
			Date fechaInicio, Date fechaFin) {
		List<CantidadTramoDemora> tramosDemora = new ArrayList<CantidadTramoDemora>();
		List<CantidadTramoDemora> tramosAnteriores = new ArrayList<CantidadTramoDemora>();
		for (CantidadTramoDemora tramo : tramosCalculo) {
			if ((!tramo.getfechaIni().before(fechaInicio))&&(!tramo.getfechaFin().after(fechaFin))) {
				tramosDemora.add(tramo);
				tramosAnteriores.add(tramo);
			} else if (!tramo.getfechaIni().before(fechaFin)) {
				break;
			} else {
				tramosAnteriores.add(tramo);
			}
		}
		//Se eliminan los tramos de demora anteriores para que no sean tratados en siguientes demoras
		tramosCalculo.removeAll(tramosAnteriores);
		return tramosDemora;
	}

	/**
	 * Método que agrupa las demoras sobre demoras generadas como demoras de resto
	 * hijas de las demoras padre dividas por fechas de vencimiento.
	 * Ambos conjuntos deben estar ordenados.
	 *
	 * @param demorasPadre
	 * @param demorasSobreDemoras
	 * @throws PlanEventoException
	 */
	private static void reagruparDemorasSobreDemoras(PlanDemora planDemora, List<EventoAutomatico> demorasPadre,
			List<EventoAutomatico> demorasSobreDemoras, Date fechaInicio,Date fechaUltimaCalendario,
			List<Date> festivos) throws PlanEventoException {
		EventoAutomatico demoraPadreUltima = null;
		/*
		 * Se añade una demora padre más a las demoras recibidas, para poder
		 * contener las demoras sobre demoras del último tramo.
		 */

		if (fechaUltimaCalendario!=null) {
			demoraPadreUltima = generarDemoraPadreParaUltimoTramo(planDemora,fechaUltimaCalendario, festivos);
			demoraPadreUltima.setFechaInicio(fechaInicio);
			demoraPadreUltima.setFechaVencimientoAjustada(demoraPadreUltima.getFechaEvento());
			demoraPadreUltima.getEventoInfo().setFechaInicioValidez(fechaInicio);
		}

		try {
			List<EventoAutomatico> demorasSobreDemorasAux = new ArrayList<EventoAutomatico>();
			demorasSobreDemorasAux.addAll(demorasSobreDemoras);
			
			List<EventoAutomatico> demorasPadreAux = new ArrayList<EventoAutomatico>(); // ICO-73424
			demorasPadreAux.addAll(demorasPadre); // ICO-73424
			
			Iterator<EventoAutomatico> iter =demorasSobreDemorasAux.iterator();
			while (iter.hasNext()){
				EventoAutomatico demoraSobreDemoraActual=iter.next();
				Date fechaDemoraAnterior = planDemora.getOperacion().getFechaFormalizacion(); // ICO-73424
				Date fechaDemoraSobreDemora = FechaUtils.convertirFecha(demoraSobreDemoraActual.getFechaEvento());
				
				for (EventoAutomatico demoraActual : demorasPadreAux) {
					Date fechaDemora = FechaUtils.convertirFecha(demoraActual.getFechaEvento());
					// INI ICO-73424
					if (demoraPadreUltima != null 
							&& !(demoraSobreDemoraActual.getFechaInicio().before(demoraPadreUltima.getFechaInicio())) 
							&& !(fechaDemoraSobreDemora.after(demoraPadreUltima.getFechaEvento())) 
							&& (fechaDemoraSobreDemora.compareTo(fechaDemora)<=0)
							&& (fechaDemoraSobreDemora.compareTo(fechaDemoraAnterior)>0)) {
						
						if (demoraSobreDemoraActual != null 
								&& demoraSobreDemoraActual.getImporte() != null
								&& demoraSobreDemoraActual.getImporte().getCantidad() != null) {
							
							if(demoraSobreDemoraActual.getFechaVencimientoAjustada() != null) {
								demoraPadreUltima.setFechaVencimientoAjustada(demoraSobreDemoraActual.getFechaVencimientoAjustada());
							}
							
							//asociamos la demora sobre demora y la demora actual a la demora padre
							if(demoraActual.getImporte().getCantidad() != null 
									&& demoraActual.getImporte().getCantidad().compareTo(BigDecimal.ZERO)>0
									&& demorasPadre.contains(demoraActual)) {
								
								if(demoraActual.getFechaVencimientoAjustada() != null) {
									demoraPadreUltima.setFechaVencimientoAjustada(demoraActual.getFechaVencimientoAjustada());
								}
								
								demoraPadreUltima.getImporte().setCantidad(demoraPadreUltima.getImporte().getCantidad().add(demoraActual.getImporte().getCantidad()));
								
								if(demoraActual.getEventosParciales() != null && !demoraActual.getEventosParciales().isEmpty()) {
									demoraPadreUltima.addEventosParcialesToSet(demoraActual.getEventosParciales());
								} else {
									demoraPadreUltima.addEventoParcialToSet(demoraActual);
								}
								demorasPadre.remove(demoraActual);
							}
							
							//sumamos el importe de la demora sobre demora a la demora padre
							if(demorasSobreDemoras.contains(demoraSobreDemoraActual)) {
								demoraPadreUltima.getImporte().setCantidad(demoraPadreUltima.getImporte().getCantidad().add(demoraSobreDemoraActual.getImporte().getCantidad()));
								
								if(demoraSobreDemoraActual.getEventosParciales() != null && !demoraSobreDemoraActual.getEventosParciales().isEmpty()) {
									demoraPadreUltima.addEventosParcialesToSet(demoraSobreDemoraActual.getEventosParciales());
								} else {
									demoraPadreUltima.addEventoParcialToSet(demoraSobreDemoraActual);
								}
								demorasSobreDemoras.remove( demoraSobreDemoraActual);
							}
							
						}
					}
					
					if(fechaDemora.after(fechaDemoraAnterior)) { 
						fechaDemoraAnterior = fechaDemora;
					}
					// FIN ICO-73424
				}

			}
		} catch (Exception e) {
			POJOValidationMessage m = new POJOValidationMessage(UtilsCalculoDemoraSobreDemora.class.getSimpleName()+e.getCause()
					+" Error al reagrupar demoras sobre demoras en demoras padre");
			throw new PlanEventoException(m);
		}
		if (demoraPadreUltima!=null) {
			
			BigDecimal cantidadTotalHijas =BigDecimal.ZERO;

			insertaDemoraPadreUltima(demorasPadre, demoraPadreUltima); //ICO-73424

			Iterator<EventoAutomatico> itDemorasHijas = demoraPadreUltima.getEventosParciales().iterator();
			while (itDemorasHijas.hasNext()) {
				cantidadTotalHijas = cantidadTotalHijas.add(itDemorasHijas.next().getImporte().getCantidad());
			}
			Importe imPadre = new ImporteImp();
			imPadre.setCantidad(cantidadTotalHijas);

			demoraPadreUltima.setImporte(imPadre);
			demoraPadreUltima.getEventoInfo().setFechaFinValidez(demoraPadreUltima.getFechaEvento());
			/*
			 * Si la ultima demora no tiene importe, no la tenemos que generar, ya que sería
			 * la demora sobre la demora, de la última demora, pero solo genera si tenemos tipo
			 * de interes.
			 */
			if (cantidadTotalHijas.compareTo(new BigDecimal(0))>0) {
				demoraPadreUltima.setPlanEvento(planDemora);
				demoraPadreUltima.setOperacion(planDemora.getOperacion());
			} else {
				demoraPadreUltima.setPlanEvento(null);
				demorasPadre.remove(demoraPadreUltima);
			}
			System.out.println("=== SALIDA reagruparDemorasSobreDemoras ===");
			for (EventoAutomatico padre : demorasPadre) {
			    System.out.println("PADRE: " + padre.getFechaInicio() + " -> " + padre.getFechaEvento() +
			        " | Parciales: " + (padre.getEventosParciales() != null ? padre.getEventosParciales().size() : 0));
			    if (padre.getEventosParciales() != null) {
			        for (EventoAutomatico parcial : padre.getEventosParciales()) {
			            System.out.println("  Parcial: fechaEvento=" + parcial.getFechaEvento() + " importe=" + parcial.getImporte().getCantidad());
			        }
			    }
			}

		}
	}
	
	// INI ICO-73424
	private static void insertaDemoraPadreUltima(List<EventoAutomatico> demorasPadre, EventoAutomatico demoraPadreUltima) {
	
		List<EventoAutomatico> demorasPadreAux = new ArrayList<EventoAutomatico>();
		demorasPadreAux.addAll(demorasPadre);
		
		for(int i = 0; i < demorasPadreAux.size(); i++) {
			
			if(FechaUtils.truncateDate(demorasPadreAux.get(i).getFechaEvento()).after(FechaUtils.truncateDate(demoraPadreUltima.getFechaEvento()))) {
				demorasPadre.add(i, demoraPadreUltima);
				break;
			} else if(i == demorasPadreAux.size() - 1) {
				demorasPadre.add(demoraPadreUltima);
			}
		}
	}
	// FIN ICO-73424
	
	private static Comparator<Cobro> comparadorCobrosPorFecha(){
		Comparator<Cobro> comparatorCobros = new Comparator<Cobro>() {  //Para ordenar la lista de cobros de menor fecha
																		//a mayor fecha
			@Override
			public int compare(Cobro o1, Cobro o2) {
				if (o1.getFechaCobro().equals(o2.getFechaCobro()))
					return 0;
				else if (o1.getFechaCobro().before(o2.getFechaCobro()))
					return -1;
				else
					return 1;
			}
		};
		
		return comparatorCobros;
	}
	
	private static Comparator<Date> comparadorFechasMenorAMayor(){
		Comparator<Date> comparatorFechas = new Comparator<Date>() {  //Para ordenar la lista de cobros de menor fecha
																		//a mayor fecha
			@Override
			public int compare(Date d1, Date d2) {
				if (d1.equals(d2))
					return 0;
				else if (d1.before(d2))
					return -1;
				else
					return 1;
			}
		};
		
		return comparatorFechas;
	}
	
}