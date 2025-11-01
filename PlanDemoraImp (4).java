package es.ico.prestamos.entidad.operacion.demora;

/* Modificaciones:
 * 
 * 20131202 - LM - ICO-9207 MEDIACION - Diferencias en demoras
 * 
 * Se utiliza el método obtenerTiposTratadosDemoraCapital para asignar como interes de demora de capital
 * el margen informado en la pestaña Datos Económicos II de la operacion
 */



@TipoPlanEvento(tipoPlanEventoEnum = TipoPlanEventoEnum.PLAN_DEMORAS)
public class PlanDemoraImp extends PlanEventoImp implements PlanDemora {

	private static final long serialVersionUID = 54796905011862991L;

	private Set<String> conceptosDemora = new HashSet<String>();

	private PlanInteresPorEvento planInteresPorDefectoVigente;

	private Date fechaPrimerVencDemPorEvento = null;

	private PlanReferenciaRenovable planReferenciaRenovable;

	private List<Date> calendarioVencimientosCalculado=new ArrayList<Date>();

	public void setFechaPrimerVencDemPorEvento(Date fechaPrimerVencDemPorEvento) {
		this.fechaPrimerVencDemPorEvento = fechaPrimerVencDemPorEvento;
	}

	public PlanDemoraImp() {
		super();
	}

	/*
	 * Se utiliza para los test
	 */
	public PlanDemoraImp(Set<String> conceptosDemora, PlanInteresPorEvento planInteresPorDefectoVigente, PlanReferenciaRenovable planReferenciaRenovable) {
		super();

		this.conceptosDemora = conceptosDemora;
		setPlanInteresPorDefectoVigente(planInteresPorDefectoVigente);

		if (planReferenciaRenovable!=null)
			planReferenciaRenovable.setPlanEvento(this);

		this.planReferenciaRenovable = planReferenciaRenovable;
	}

	public Set<String> getConceptosDemora() {
		return conceptosDemora;
	}

	private Set<String> getConceptosDemoraResto() {
		Set<String> conceptosResto = new HashSet<String>(getConceptosDemora());
		conceptosResto.remove(ConceptoDemoraEnum.CAPITAL.getCodigo());
		return conceptosResto;
	}

	public void setConceptosDemora(Set<String> conceptosDemora) {
		this.conceptosDemora = conceptosDemora;
	}

	public PlanInteresPorEvento getPlanInteresPorDefectoVigente() {
		return planInteresPorDefectoVigente;
	}

	public void setPlanInteresPorDefectoVigente(PlanInteresPorEvento planInteresPorDefectoVigente) {
		if (planInteresPorDefectoVigente!=null)
			planInteresPorDefectoVigente.setPlanEvento(this);
		this.planInteresPorDefectoVigente = planInteresPorDefectoVigente;
	}

	/**
	 * Genera un plan de demora a partir de la request param requestParameters
	 *
	 * @param raiz
	 * @param mensajes
	 *            de error
	 * @return PlanDemoraImp
	 * @throws POJOValidationException
	 */

	public static PlanDemora generate(Map<String, String[]> requestParameters, String raiz,
			PlanInteresPorEventoImp planInteresEnPlanDemora) throws POJOValidationException {

		HashSet<POJOValidationMessage> mensajes = new HashSet<POJOValidationMessage>();

		PlanDemora planDemora = new PlanDemoraImp(requestParameters, raiz, mensajes, planInteresEnPlanDemora);

		if (!mensajes.isEmpty()) {
			throw new POJOValidationException(mensajes);
		}

		return planDemora;

	}

	/**
	 * Constructor especifico para el uso de factorias. No se llama al
	 * constructor del super, porque el Set de tipos de interes tiene un
	 * tratamiento distinto en cada caso, que se hará en el Manager.
	 */

	public PlanDemoraImp(Map<String, String[]> request, String raiz, HashSet<POJOValidationMessage> mensajes,
			PlanInteresPorEventoImp planInteresEnPlanDemora) throws POJOValidationException {

		try {// conceptos de demora
			String[] codConceptosDemora = FactoriaUtils.getCampos(request, raiz,
					ConstantesFD.REQUEST_FD_PLANDEMORAS_CONCEPTODEMORA);
			if (codConceptosDemora!=null) {
				for (int i = 0; i<codConceptosDemora.length; i++) {
					this.conceptosDemora.add(codConceptosDemora[i]);
				}
			}
		} catch (Exception e) {
			LOG.error(PlanDemoraImp.class.getName()+" conceptosDemora "+ConstantesErrorAdmin.NO_VALIDO);
			mensajes.add(new POJOValidationMessage("conceptosDemora", ConstantesError.FORMATO_NO_VALIDO_CON_CAMPO));
		}

		setPlanInteresPorDefectoVigente(planInteresEnPlanDemora);

		Boolean esFijo = null;
		String fijacion=FactoriaUtils.getCampo(request,(raiz + ConstantesFD.FD_REQ_PA_PLANINTERES_OPER_FIJACION_MANUAL_DEMORA));
		String referenciaDemoraAsTipoInteres=FactoriaUtils.getCampo(request,(raiz + ConstantesFD.FD_REQ_PA_PLANINTERES_OPER_FIJACION_REF_DEMORA_AS_TIPO_INTERES));
		boolean isFijacion = (fijacion!=null && fijacion.equals("true")) ? true : false;
		
		try {//check de tipo fijo
			// INI - ICO-60274 - 10/06/2020 - Antes si fijación manual estaba checkeado se daba por hecho que era variable
			/*if (fijacion!=null && fijacion.equals("true")){
				esFijo=false;
			}else {
				esFijo = FormatUtils.parseBoolean(FactoriaUtils.getCampo(request,
						(raiz+ConstantesFD.REQUEST_TIPOFIJO_RADIOBUTTON)));
			}*/
			
			esFijo = FormatUtils.parseBoolean(FactoriaUtils.getCampo(request,
					(raiz+ConstantesFD.REQUEST_TIPOFIJO_RADIOBUTTON)));
			// FIN - ICO-60274 - 10/06/2020
			

		} catch (Exception e) {
			mensajes.add(new POJOValidationMessage("esFijo", ConstantesError.FORMATO_NO_VALIDO_CON_CAMPO));
		}
		
		PlanReferenciaRenovable planReferenciaRenovable = new PlanReferenciaRenovableImp();
		planReferenciaRenovable.setFijacionManualDemoras(fijacion!=null && fijacion.equals("true"));
		planReferenciaRenovable.setReferenciaDemoraAsTipoInteres(referenciaDemoraAsTipoInteres!=null && referenciaDemoraAsTipoInteres.equals("true"));
		
		if (!esFijo) {//es variable y por tanto tiene renovación
			setPlanReferenciaRenovable(planReferenciaRenovable.generate(request, raiz, mensajes));
		}

		if(!isFijacion){
			validate(planInteresEnPlanDemora);
		}
	}

	/*
	 * Metodo de validacion, en la referencia renovable se necesitan los datos
	 * de periodicidad de renovacion y la fecha para fijar el tipo de interés
	 */

	public static void validate(PlanInteresPorEvento planInteresPorEventoEnPlanDemora)
			throws POJOValidationException {

		HashSet<POJOValidationMessage> mensajes = new HashSet<POJOValidationMessage>();

//		if (conceptosDemora==null) {
//			LOG.error(PlanDemoraImp.class.getName()+" conceptosDemora "+ConstantesErrorAdmin.NO_VALIDO);
//			mensajes.add(new POJOValidationMessage("conceptosDemora", ConstantesError.NULO));
//		} else if (conceptosDemora.isEmpty()) {
//			LOG.error(PlanDemoraImp.class.getName()+" conceptosDemora "+ConstantesErrorAdmin.NO_VALIDO);
//			mensajes.add(new POJOValidationMessage("conceptosDemora", ConstantesError.NULO));
//		}
		
		if (planInteresPorEventoEnPlanDemora==null) {
			LOG.error("PlanDemoraImp.planInteresPorEvento"+ConstantesErrorAdmin.NULO);
			mensajes.add(new POJOValidationMessage("planInteresPorEvento", ConstantesError.ERROR_TIPO_INTERES_NULO));
		} else {
			PlanInteresPorEventoImp.validatePeriodicidadLiqYBaseCalculo(
					planInteresPorEventoEnPlanDemora.getPeriodicidadLiquidacion(),
					planInteresPorEventoEnPlanDemora.getBaseCalculo(), mensajes);
		}

		if (!mensajes.isEmpty()) {
			throw new POJOValidationException(mensajes);
		}
	}

	@Override
	public String getTipoPlanEnOperacion() {
		return getTipoPlanEvento().getCodigo();
		// return ConstantesFD.TIPO_PLAN_DEMORA;
	}
	
	
	private List<Date> obtenerAmortizacionesVoluntarias (List<Evento> amortizaciones){
		List <Date> fechasAVAS=new ArrayList<Date>();
	    Iterator<Evento> itEventos  = amortizaciones.iterator();
        while(itEventos.hasNext()) {
            Evento evento = itEventos.next();
            if(evento instanceof AmortizacionAnticipadaVoluntariaImp) {
            	fechasAVAS.add(evento.getFechaEvento());	            	
            }	        	
        }
		return fechasAVAS;
	}

	/**
	 * Algoritmo de generación de demoras a partir del plan.
	 *
	 * @author salonso
	 *
	 * @param fechaInicioEjecucionEventos
	 * @param eventosPadre
	 * @return Set<Eventos>
	 * @throws PlanEventoException
	 */
	public List<EventoAutomatico> doEventos(Date fechaInicioEjecucionEventos, SaldosTotalesOp saldos,
			EventosOperacion eventosOperacion, List<Date> festivos, Map<Date, List<Cobro>> cobros)
			throws PlanEventoException {

		// DEBUG VPO: Log entry for debugging delay-on-delay recalculation issue
		if(this.getOperacion() instanceof OperacionVPO) {
			System.out.println("VPO doEventos - Fecha ejecución: " + fechaInicioEjecucionEventos);
		}

		Date fechaInicioEjecucionEventosAux=new Date();
		fechaInicioEjecucionEventosAux=fechaInicioEjecucionEventos;

		if(this.getOperacion() instanceof OperacionFD || (this.getOperacion()  instanceof OperacionFF) || (this.getOperacion()  instanceof OperacionPS)|| (this.getOperacion()  instanceof OperacionVPO)) {
    		return doEventosDirectos (fechaInicioEjecucionEventos, saldos, eventosOperacion, festivos, cobros);
    	
		}else if(this.getOperacion() instanceof OperacionICD && this.getOperacion().getCodigoHost() != null){
			return doEventosICD (fechaInicioEjecucionEventos, saldos, eventosOperacion, festivos, cobros);
			
    	} else {
	 		SaldosTotalesOp saldosTotalesAux = null;
			try {
				saldosTotalesAux = (SaldosTotalesOp)saldos.clone();
			} catch (CloneNotSupportedException e1) {
				LOG.info(this.getClass().getName()+ ".doEventos Error al hacer saldos.clone() ", e1);
			}
	
			List<Date> calendarioDemoras = null;
			List <Date> fechasAVAS = null;
			//-------------> Obtencion del calendario de vencimientos.
			/**
			 * Primero obtenemos el calendario completo de demoras. La segunda fecha
			 * obtenida del calendario, es la primera de la fecha de liquidación de
			 * demoras, ya que, la primera fecha del calendario, establece el inicio
			 * del periodo de cálculo de la primera liquidación.
			 */
	
			if(!this.isVariable() && eventosOperacion.getDemorasAnteriores() != null) { 
				Date fechaUltimaLiquidacion = null;
				for(EventoAutomatico ea : eventosOperacion.getDemorasAnteriores()) {
					if(fechaUltimaLiquidacion == null) {
						fechaUltimaLiquidacion = ea.getFechaEvento();
					}
					else {
						if(fechaUltimaLiquidacion.before(ea.getFechaEvento())) {
							fechaUltimaLiquidacion = ea.getFechaEvento();
						}
					}
				}
				if(fechaUltimaLiquidacion != null && fechaUltimaLiquidacion.before(fechaInicioEjecucionEventos)) {
					fechaInicioEjecucionEventos = fechaUltimaLiquidacion;
				}
			}
	
			try {
				List<Evento> liquidacionesManuales = eventosOperacion.getLiquidacionInteresesManuales();
				Set<TipoInteresFijado> tiposInteresAplicables = this.getPlanInteresPorDefectoVigente().getTipoInteres();
				Date fechaLimite = fechaFinLiquidacionesPorTipoInteres(tiposInteresAplicables);
				//ICO-73341 tener en cuenta la fecha fin tipos en FAD
				List <Date> vencimientos=getCalendarioVencimientosNew(fechaLimite);			
				List<Evento> amortizaciones= eventosOperacion.getAmortizaciones();	
				fechasAVAS=obtenerAmortizacionesVoluntarias (amortizaciones);
				
				Evento liquidacionAnterior=eventosOperacion.getUltimaDemora(fechaInicioEjecucionEventos, vencimientos);

				calendarioDemoras = getCalendarioVencimientosIntereses(fechaInicioEjecucionEventos, fechaLimite, liquidacionesManuales, liquidacionAnterior, festivos);
				
	
			} catch (POJOValidationException e) {
				throw new PlanEventoException(e.getCause()+"Error al construir el calendario de vencimientos");
			}
	
			/**
			 * Se ajustan las fechas. Antes de realizar los cálculos las fechas de
			 * eventos son ajustadas por convención de día hábil, en el caso de que
			 * sea ajustada la operacion.
			 */
			//calendarioDemoras = getCalendarioAjustado(calendarioDemoras, getOperacion().getPlanAjustableDias(),
			//		festivos);
			/**
			 * Se filtran las fechas por fecha de ejecución.
			 */
	//		calendarioDemoras = filtrarCalendarioPorFechaInicio(fechaInicioEjecucionEventos, calendarioDemoras);
	//		calendarioDemoras = filtrarCalendarioParaSimulacion(calendarioDemoras);
	
			List<EventoAutomatico> demoras = calcularDemoras(calendarioDemoras, saldosTotalesAux, festivos, fechasAVAS, cobros);
	
			/**
			 * Generamos demoras sobre demoras.
			 * Las demoras sobre demoras se asociaran como liquidaciones hijas
			 * por el concepto de resto a las liquidaciones padre.
			 */
	
			if (getConceptosDemora().contains(ConceptoDemoraEnum.DEMORAS.getCodigo())) {
	
				if(eventosOperacion.getDemorasAnteriores()!=null){
					demoras.addAll(eventosOperacion.getDemorasAnteriores());
					Collections.sort(demoras, new EventoFechaEventoComparator());
				}
				
				PlanInteresPorEvento pie = this.getPlanInteresPorDefectoVigente();
				Set<TipoInteresFijado> tipos = pie.getTipoInteres();
					
				Date fechaInicioEjecucionEventosAuxiliar=null;
				if(eventosOperacion.isCobro()) {
					fechaInicioEjecucionEventosAuxiliar = FechaUtils.restaDia(fechaInicioEjecucionEventos);
				}else{
					fechaInicioEjecucionEventosAuxiliar=fechaInicioEjecucionEventos;
				}
				
				for (TipoInteresFijado ti : tipos){	
					
					boolean existeDemora=false;
					
					for (EventoAutomatico dem : demoras){
						if((dem.getFechaEvento().equals(FechaUtils.sumarDias(ti.getFecha_final(), 1)) &&
							dem.getFechaInicio().equals(ti.getFecha_inicio()) && ti.isTotal()) 
							|| ti.getFecha_inicio().before(fechaInicioEjecucionEventosAuxiliar)){
							existeDemora=true;
							break;
						}		
					}
					if(!existeDemora){
						try {
							LiqDemoras demoraAux = new LiqDemorasImp();
							demoraAux.setImporte(new ImporteImp(BigDecimal.ZERO));
							demoraAux.setFechaInicio(ti.getFecha_inicio());
							demoraAux.setFechaEvento(FechaUtils.sumarDias(ti.getFecha_final(), 1));
							auditarEventoAutomatico(demoraAux);
							demoraAux.setEventoInfo(new EventoInfoImp());
							demoraAux.getEventoInfo().setPorcentajeAplicado(ti.getValor_fijado());
							demoraAux.getEventoInfo().setFechaFinValidez(demoraAux.getFechaEvento());
							demoraAux.getEventoInfo().setFechaInicioValidez(demoraAux.getFechaInicio());
							demoraAux.getEventoInfo().getImporteBase().setCantidad(BigDecimal.ZERO);
							demoraAux.setOperacion(getOperacion());
							demoraAux.setPlanEvento(this);
							demoras.add(demoraAux);
						} catch (Exception e) {
							POJOValidationMessage m = new POJOValidationMessage(UtilsCalculoDemoraSobreDemora.class.getSimpleName()+e.getCause()
									+" Error crear la demora auxiliar para el cálculo de demora sobre demora");
							throw new PlanEventoException(m);
						}
						
					}		
				}
				
				Collections.sort(demoras, new EventoFechaEventoComparator());
				
	
				if (demoras.size()>0) {
					//Si vengo de un cobro, resto un día
					if(eventosOperacion.isCobro()) {
						fechaInicioEjecucionEventosAux = FechaUtils.restaDia(fechaInicioEjecucionEventosAux);
					}
					UtilsCalculoDemoraSobreDemora.calcularDemoraSobreDemora(this,fechaInicioEjecucionEventosAux, demoras, saldosTotalesAux,
							eventosOperacion, festivos, calendarioDemoras, cobros );
				}
				
				Iterator<EventoAutomatico> it = demoras.iterator();
				while(it.hasNext()){
					EventoAutomatico ev = it.next();
				if(ev.getImporte().getCantidad().equals(BigDecimal.ZERO)){
					it.remove();
					}
				}
			}
	
			if(eventosOperacion.getDemorasAnteriores()!=null){
				for (EventoAutomatico demora: eventosOperacion.getDemorasAnteriores()){
					demoras.remove(demora);
				}
			}
	
			//redondear ahora
			for(EventoAutomatico ev: demoras){
				try {
					//ICO - 12278 Cuando la moneda esté dentro del enum con el valor a true por ejemplo la moneda JPY ( Yenes japoneses ) se le realizará al importe de la demoras un redondeo.
					if(EnumRedondeoMoneda.esRedondeable(this.getOperacion().getDivisaOperacion().getCodigo().intValue())){
						ev.getImporte().setCantidad(FormatUtils.redondear(ev.getImporte().getCantidad(), 0));
					}else{
						ev.getImporte().setCantidad(FormatUtils.redondear(ev.getImporte().getCantidad(), 2));
					}
				} catch (Exception e) {
					throw new PlanEventoException(new POJOValidationMessage("Se ha producido un error redondeando el importe"));
				}
			}
	
			List<EventoAutomatico> listasDemoras = eventosOperacion.getDemorasAnteriores();
			if (listasDemoras!=null){
				Iterator<EventoAutomatico> it = listasDemoras.iterator();
				while(it.hasNext()) {
					EventoAutomatico ev = it.next();
					for (int i=0; i<demoras.size(); i++){
						if( FechaUtils.truncateDate(ev.getFechaEvento()).compareTo(FechaUtils.truncateDate(demoras.get(i).getFechaEvento()))==0 ) {
								demoras.remove(i);
						}	
					}						
				}
			}
					
			return demoras;
    	}
	}
	
	public List<EventoAutomatico> doEventosDirectos(Date fechaInicioEjecucionEventos, SaldosTotalesOp saldos,
			EventosOperacion eventosOperacion, List<Date> festivos, Map<Date, List<Cobro>> cobros)
			throws PlanEventoException {

		// DEBUG VPO: Log entry for debugging delay-on-delay recalculation issue
		if(this.getOperacion() instanceof OperacionVPO) {
			System.out.println("VPO doEventosDirectos - Fecha ejecución: " + fechaInicioEjecucionEventos);
		}

		//INI ICO-62994
		if(this.getOperacion() instanceof OperacionFD && !esCarteraTraspasada(this.getOperacion()) &&
				eventosOperacion.isBajaCobro()){
			fechaInicioEjecucionEventos = FechaUtils.sumaUnDiaCalendario(fechaInicioEjecucionEventos);
		}

		Date fechaInicioEjecucionReal = fechaInicioEjecucionEventos;
		Date fechaAux = null;
		Boolean isFechaLiqAnt = false;
		Boolean cambiaFecha = false;

		Calendar hoy = Calendar.getInstance();
		hoy.set(Calendar.HOUR_OF_DAY, 0);
		hoy.set(Calendar.MINUTE, 0);
		hoy.set(Calendar.SECOND, 0);
		hoy.set(Calendar.MILLISECOND, 0);
 		//FIN ICO-62994
		SaldosTotalesOp saldosTotalesAux = null;
 		
		try {
			saldosTotalesAux = (SaldosTotalesOp)saldos.clone();
		} catch (CloneNotSupportedException e1) {
			LOG.info(this.getClass().getName()+ ".doEventos Error al hacer saldos.clone() ", e1);
		}

		List<Date> calendarioDemoras = new ArrayList<Date>();;
		List <Date> fechasAVAS = null;
		//-------------> Obtencion del calendario de vencimientos.
		/**
		 * Primero obtenemos el calendario completo de demoras. La segunda fecha
		 * obtenida del calendario, es la primera de la fecha de liquidación de
		 * demoras, ya que, la primera fecha del calendario, establece el inicio
		 * del periodo de cálculo de la primera liquidación.
		 */

		if((!this.isVariable() 
				|| ((this.getOperacion() instanceof OperacionFD || this.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(this.getOperacion()))) //ICO-62994
				&& eventosOperacion.getDemorasAnteriores() != null) { 
			Date fechaUltimaLiquidacion = null;
			for(EventoAutomatico ea : eventosOperacion.getDemorasAnteriores()) {
				if(fechaUltimaLiquidacion == null) {
					fechaUltimaLiquidacion = ea.getFechaEvento();
				}
				else {
					if(fechaUltimaLiquidacion.before(ea.getFechaEvento())) {
						fechaUltimaLiquidacion = ea.getFechaEvento();
					}
				}
			}
			if(fechaUltimaLiquidacion != null && fechaUltimaLiquidacion.before(fechaInicioEjecucionEventos)) {
				fechaInicioEjecucionEventos = fechaUltimaLiquidacion;
				isFechaLiqAnt = true; //ICO-62994
			}
		}

		try {
			Set<TipoInteresFijado> tiposInteresAplicables = this.getPlanInteresPorDefectoVigente().getTipoInteres();
			Date fechaLimite = fechaFinLiquidacionesPorTipoInteres(tiposInteresAplicables);	
			//ICO-73341 tener en cuenta la fecha fin tipos en FAD
			List <Date> vencimientos=getCalendarioVencimientosNew(fechaLimite);		
			List<Evento> amortizaciones= eventosOperacion.getAmortizaciones();	
			fechasAVAS=obtenerAmortizacionesVoluntarias (amortizaciones);
			
			Evento liquidacionAnterior=eventosOperacion.getUltimaDemora(fechaInicioEjecucionEventos, vencimientos);

			
			//Añado los tipos de interés fijados de la demora y de los intereses. Siempre que haya una fijación de tipos de interés, se creará demora
			tiposInteresAplicables = getPlanInteresPorDefectoVigente().obtenerTiposTratados(null);
			//ICO-60214 Se comenta pq añade los tipos de interés y estamos en demoras
//			tiposInteresAplicables.addAll(this.getOperacion().getPlanInteres().getPlanInteresPorDefectoVigente().obtenerTiposTratados(null));
			
			Boolean esSiguiente = true;
			
			//ICO-60214 igualPeriodo true cuando periodo renovación demoras <= periodo liquidación demoras
			Boolean igualPeriodo = false;
			if ((getPlanInteresPorDefectoVigente().getPlanEvento() instanceof PlanDemoraImp) && 
				((PlanDemoraImp)getPlanInteresPorDefectoVigente().getPlanEvento()).getPlanReferenciaRenovable() != null  &&
				getPlanInteresPorDefectoVigente().getPeriodicidadLiquidacion()!=null &&
				((PlanDemoraImp)getPlanInteresPorDefectoVigente().getPlanEvento()).getPlanReferenciaRenovable().getPeriodicidadRenovacionTipo().getNumMeses() <= getPlanInteresPorDefectoVigente().getPeriodicidadLiquidacion().getNumMeses()) {	
					igualPeriodo = true;					
			}
				
			if (fechaLimite!=null){		
				Date fechaIni = null;
				Date fechaInicioEjecucionEventosAux = new Date();
				if(eventosOperacion.isCobro()){
					fechaInicioEjecucionEventosAux=FechaUtils.restaDia(fechaInicioEjecucionEventos);
				}else{
					fechaInicioEjecucionEventosAux=fechaInicioEjecucionEventos;
				}
				
				
				Iterator<TipoInteresFijado> ti=tiposInteresAplicables.iterator();
			    while (ti.hasNext()) {
			    	TipoInteresFijado tf= (TipoInteresFijado) ti.next();
			    	if ( tf.isTotal() && FechaUtils.sumarDias(tf.getFecha_final(),1).compareTo(FechaUtils.restarDias(fechaInicioEjecucionEventosAux, 1))>=0 ) {
			    		if((this.getOperacion() instanceof OperacionFD || this.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(this.getOperacion())) { //ICO-62994
			    			if(!hoy.getTime().before(FechaUtils.sumarDias(tf.getFecha_final(),1)) || (hoy.getTime().before(FechaUtils.sumarDias(tf.getFecha_final(),1)) && esSiguiente)) {
			    				calendarioDemoras.add(FechaUtils.sumarDias(tf.getFecha_final(),1));
			    				
			    				if(hoy.getTime().before(FechaUtils.sumarDias(tf.getFecha_final(),1))){
			    					esSiguiente = false;
			    				}
			    			}
			    		} else {
			    			calendarioDemoras.add(FechaUtils.sumarDias(tf.getFecha_final(),1));
			    		}
			    		
			    		if(!tf.isDisposicion() && fechaIni == null) {
				    		fechaIni = tf.getFecha_inicio();
				    	}
			    	}
			    	
			    }	    
			    
			    fechaIni = fechaIni != null ? fechaIni : getPlanInteresPorDefectoVigente().getFechaPrimerEvento();
				
				if(liquidacionAnterior != null && liquidacionAnterior.getFechaEvento() != null && !liquidacionAnterior.getIsDemoraManual()) { //ICO-63282 que no tenga en cuenta las manuales
					calendarioDemoras.add(liquidacionAnterior.getFechaEvento());
				}
				else if(fechaIni != null){
					if (!vencimientos.contains(fechaIni) || igualPeriodo){ //ICO-60214 Ajustamos la fecha inicio de la liquidación cuando no coincide con la fijación de tipo
						Date fechaAnteriorTotal = null;
						
						for(Date fechaV : vencimientos) {
							if(fechaIni.compareTo(fechaV)>=0)								
								fechaAnteriorTotal = fechaV;
							else
								break;
						}
						
						if (fechaAnteriorTotal != null){
							fechaIni = fechaAnteriorTotal;
						}
						
					} 
					
					calendarioDemoras.add(fechaIni);
				}
				
				Collections.sort(calendarioDemoras);
			}
			

		} catch (POJOValidationException e) {
			throw new PlanEventoException(e.getCause()+"Error al construir el calendario de vencimientos");
		}

		/**
		 * Se ajustan las fechas. Antes de realizar los cálculos las fechas de
		 * eventos son ajustadas por convención de día hábil, en el caso de que
		 * sea ajustada la operacion.
		 */
		//calendarioDemoras = getCalendarioAjustado(calendarioDemoras, getOperacion().getPlanAjustableDias(),
		//		festivos);
		/**
		 * Se filtran las fechas por fecha de ejecución.
		 */
//		calendarioDemoras = filtrarCalendarioPorFechaInicio(fechaInicioEjecucionEventos, calendarioDemoras);
//		calendarioDemoras = filtrarCalendarioParaSimulacion(calendarioDemoras);
		
		System.out.println("=== DEMORAS ANTERIORES antes de calcularDemoras ===");
		List<EventoAutomatico> demorasAnteriores = eventosOperacion.getDemorasAnteriores();
		if (demorasAnteriores == null || demorasAnteriores.isEmpty()) {
		    System.out.println("No hay demoras anteriores.");
		} else {
		    for (EventoAutomatico demora : demorasAnteriores) {
		        System.out.println("Demora PADRE: " + demora.getFechaInicio() + " -> " + demora.getFechaEvento()
		            + " | Importe: " + (demora.getImporte() != null ? demora.getImporte().getCantidad() : "null")
		            + " | Parciales: " + (demora.getEventosParciales() != null ? demora.getEventosParciales().size() : 0));
		        if (demora.getEventosParciales() != null && !demora.getEventosParciales().isEmpty()) {
		            for (EventoAutomatico parcial : demora.getEventosParciales()) {
		                System.out.println("  Parcial: fechaEvento=" + parcial.getFechaEvento()
		                    + " importe=" + (parcial.getImporte() != null ? parcial.getImporte().getCantidad() : "null"));
		            }
		        }
		    }
		}
		System.out.println("=== FIN DEMORAS ANTERIORES ===");
		
		// ... código previo ...
		// calendarioDemoras ya está construido aquí
		// Añadir la fecha anterior si falta

		/*if (calendarioDemoras != null && !calendarioDemoras.isEmpty()) {
		    Date fechaPrimera = calendarioDemoras.get(0);
		    Calendar cal = Calendar.getInstance();
		    cal.setTime(fechaPrimera);
		    // Suponiendo periodicidad mensual, restamos un mes
		    cal.add(Calendar.MONTH, -1);
		    Date fechaAnterior = cal.getTime();

		    // Si no está ya en el calendario, la añadimos
		    boolean existe = false;
		    for (Date d : calendarioDemoras) {
		        if (FechaUtils.truncateDate(d).equals(FechaUtils.truncateDate(fechaAnterior))) {
		            existe = true;
		            break;
		        }
		    }
		    if (!existe) {
		        calendarioDemoras.add(0, fechaAnterior);
		    }
		    Collections.sort(calendarioDemoras);
		}*/


		List<EventoAutomatico> demoras = calcularDemoras(calendarioDemoras, saldosTotalesAux, festivos, fechasAVAS, cobros);

		/**
		 * Generamos demoras sobre demoras.
		 * Las demoras sobre demoras se asociaran como liquidaciones hijas
		 * por el concepto de resto a las liquidaciones padre.
		 */

		if (getConceptosDemora().contains(ConceptoDemoraEnum.DEMORAS.getCodigo())) {

		if(eventosOperacion.getDemorasAnteriores()!=null){
			// FIX VPO: Filtrar demorasAnteriores para excluir las que se superponen con demoras recalculadas
			if((this.getOperacion() instanceof OperacionFD || this.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(this.getOperacion())) {
				for(EventoAutomatico demoraAnterior : eventosOperacion.getDemorasAnteriores()) {
					// Solo añadir si NO se superpone con las demoras nuevas calculadas
					boolean seSuperpone = false;
					for(EventoAutomatico nuevaDemora : demoras) {
						if(demoraAnterior.getFechaInicio().compareTo(nuevaDemora.getFechaEvento()) < 0
						   && demoraAnterior.getFechaEvento().compareTo(nuevaDemora.getFechaInicio()) > 0) {
							seSuperpone = true;
							break;
						}
					}
					if(!seSuperpone) {
						demoras.add(demoraAnterior);
					}
				}
			} else {
				demoras.addAll(eventosOperacion.getDemorasAnteriores());
			}
			Collections.sort(demoras, new EventoFechaEventoComparator());
		}

			PlanInteresPorEvento pie = this.getPlanInteresPorDefectoVigente();
			Set<TipoInteresFijado> tipos = pie.getTipoInteres();
				
			Date fechaInicioEjecucionEventosAux=null;
			if(eventosOperacion.isCobro()) {
				fechaInicioEjecucionEventosAux = FechaUtils.restaDia(fechaInicioEjecucionEventos);
			}else{
				fechaInicioEjecucionEventosAux=fechaInicioEjecucionEventos;
			}
			
			for (TipoInteresFijado ti : tipos){	
				
				boolean existeDemora=false;
				
				for (EventoAutomatico dem : demoras){
					if((dem.getFechaEvento().equals(FechaUtils.sumarDias(ti.getFecha_final(), 1)) &&
						ti.isTotal()) 
						//dem.getFechaInicio().equals(ti.getFecha_inicio()) && 
						|| FechaUtils.sumarDias(ti.getFecha_final(), 1).before(fechaInicioEjecucionEventosAux)){
						if(dem.getFechaInicio().compareTo(ti.getFecha_inicio())<0){
							dem.setFechaInicio(ti.getFecha_inicio());
						}
						existeDemora=true;
						break;
					}		
				}
				if(!existeDemora){
					try {
						LiqDemoras demoraAux = new LiqDemorasImp();
						demoraAux.setImporte(new ImporteImp(BigDecimal.ZERO));
						demoraAux.setFechaInicio(ti.getFecha_inicio());
						demoraAux.setFechaEvento(FechaUtils.sumarDias(ti.getFecha_final(), 1));
						auditarEventoAutomatico(demoraAux);
						demoraAux.setEventoInfo(new EventoInfoImp());
						demoraAux.getEventoInfo().setPorcentajeAplicado(ti.getValor_fijado());
						demoraAux.getEventoInfo().setFechaFinValidez(demoraAux.getFechaEvento());
						demoraAux.getEventoInfo().setFechaInicioValidez(demoraAux.getFechaInicio());
						demoraAux.getEventoInfo().getImporteBase().setCantidad(BigDecimal.ZERO);
						demoraAux.setOperacion(getOperacion());
						demoraAux.setPlanEvento(this);
						demoras.add(demoraAux);
					} catch (Exception e) {
						POJOValidationMessage m = new POJOValidationMessage(UtilsCalculoDemoraSobreDemora.class.getSimpleName()+e.getCause()
								+" Error crear la demora auxiliar para el cálculo de demora sobre demora");
						throw new PlanEventoException(m);
					}
					
				}		
			}
			
			Collections.sort(demoras, new EventoFechaEventoComparator());
			
			if (!demoras.isEmpty()) {
				//INI ICO-62994
				if((this.getOperacion() instanceof OperacionFD || this.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(this.getOperacion())) {
					if(fechaAux == null) {
						for(Date fechaDemora : calendarioDemoras) {
							if(fechaInicioEjecucionReal.compareTo(fechaDemora) >= 0) {
								fechaAux = fechaDemora;
							}
						}
					}
					
					if(fechaAux != null && fechaAux.compareTo(fechaInicioEjecucionEventos) != 0) {
						fechaInicioEjecucionEventos = fechaAux;
						cambiaFecha = true;
					}
					
					if((!isFechaLiqAnt && !eventosOperacion.isBajaCobro()) || cambiaFecha) {
						fechaInicioEjecucionEventos = FechaUtils.restaDia(fechaInicioEjecucionEventos); //Siempre hay que restar un día en este caso
					}
				}//FIN ICO-62994
				else {
					//Si vengo de un cobro, resto un día
					if(eventosOperacion.isCobro()) {
						fechaInicioEjecucionEventos = FechaUtils.restaDia(fechaInicioEjecucionEventos);
					}
				}
				
				UtilsCalculoDemoraSobreDemora.calcularDemoraSobreDemora(this,fechaInicioEjecucionEventos, demoras, saldosTotalesAux,
						eventosOperacion, festivos, calendarioDemoras, cobros );
				
				}
			System.out.println("LOG1 - DESPUES_DSD: " + demoras.size());
			
			Iterator<EventoAutomatico> it = demoras.iterator();
			
			while(it.hasNext()){
				EventoAutomatico ev = it.next();
				if(ev.getImporte().getCantidad().equals(BigDecimal.ZERO)){
					it.remove();
				}
			}
		}
		System.out.println("=====Antes del return====");
		int count = 0;
		
		for(EventoAutomatico d : demoras) {
			if(d.getFechaEvento().toString().contains("Feb 11") &&
					d.getFechaEvento().toString().contains("2015")) {
				count ++;
				System.out.println("11/02 " + count + "ID= " +d.getId() + "|Importe = " +d.getImporte().getCantidad());
			}
		}
		
		System.out.println("Total 11/02 " + count);
		
		if(eventosOperacion.getDemorasAnteriores()!=null && !demoras.isEmpty()){
			for (EventoAutomatico demora: eventosOperacion.getDemorasAnteriores()){
				demoras.remove(demora);
			}
		}
		
		
		
		System.out.println("LOG1 - DESPUES_bloque1: " + demoras.size());System.out.println(eventosOperacion.getDemorasAnteriores());
		
		//INI ICO-62994
		Iterator<EventoAutomatico> it = demoras.iterator();
		
		if((this.getOperacion() instanceof OperacionFD || this.getOperacion() instanceof OperacionVPO) && !esCarteraTraspasada(this.getOperacion())) {
			while(it.hasNext()){
				EventoAutomatico ev = it.next();
				if(ev.getFechaInicio().after(hoy.getTime())){
					it.remove();
				}
			}
		}
		//FIN ICO-62994
		//redondear ahora
		for(EventoAutomatico ev: demoras){
			try {
				//ICO - 12278 Cuando la moneda esté dentro del enum con el valor a true por ejemplo la moneda JPY ( Yenes japoneses ) se le realizará al importe de la demoras un redondeo.
				if(EnumRedondeoMoneda.esRedondeable(this.getOperacion().getDivisaOperacion().getCodigo().intValue())){
					ev.getImporte().setCantidad(FormatUtils.redondear(ev.getImporte().getCantidad(), 0));
				}else{
					ev.getImporte().setCantidad(FormatUtils.redondear(ev.getImporte().getCantidad(), 2));
				}
			} catch (Exception e) {
				throw new PlanEventoException(new POJOValidationMessage("Se ha producido un error redondeando el importe"));
			}
		}

		List<EventoAutomatico> listasDemoras = eventosOperacion.getDemorasAnteriores();
		if (listasDemoras!=null && !demoras.isEmpty()){
			it = listasDemoras.iterator();
			while(it.hasNext()) {
				EventoAutomatico ev = it.next();
				for (int i=0; i<demoras.size(); i++){
					if( FechaUtils.truncateDate(ev.getFechaEvento()).compareTo(FechaUtils.truncateDate(demoras.get(i).getFechaEvento()))==0 ) {
							demoras.remove(i);
					}	
				}						
			}
		}
		
		System.out.println("LOG3 - DESPUES_BLOQUE 3: " + demoras.size());
		
		//ICO-63282 para FF las fechas son correctas
		if(!(this.getOperacion()  instanceof OperacionFF)) {
			//ICO-37671 								  En directos puede haber tramos sin tipo de demora, 
			for (EventoAutomatico dem: demoras){		//así dejamos las fechas cuadradas
				Set<EventoAutomatico> demorasparciales=dem.getEventosParciales();
				Date fechaInicio=null;
				Date fechaFin=null;
				for(EventoAutomatico dempar: demorasparciales){
					if( fechaInicio == null || dempar.getFechaInicio().before(fechaInicio)){
						fechaInicio=dempar.getFechaInicio();
					}
					if( fechaFin == null || dempar.getFechaEvento().after(fechaFin)){
						fechaFin=dempar.getFechaEvento();
					}
				}				
				dem.setFechaInicio(fechaInicio);
				dem.getEventoInfo().setFechaInicioValidez(fechaInicio);
				
				dem.setFechaEvento(fechaFin);
				dem.getEventoInfo().setFechaFinValidez(fechaFin);
			}
		}
		
		
		if(this.getPlanInteresPorDefectoVigente().getTipoInteres().size()>0){
			ArrayList <Date> fechasFinTipos=new ArrayList<Date>();
			for(TipoInteresFijado tif: this.getPlanInteresPorDefectoVigente().getTipoInteres()){
				fechasFinTipos.add(FechaUtils.sumarDias(tif.getFecha_final(), 1));
			}
			Collections.sort(fechasFinTipos);
			
			for(EventoAutomatico dem: demoras){
				if(!fechasFinTipos.contains(dem.getFechaEvento())){
					for(Date d: fechasFinTipos){
						if(d.after(dem.getFechaEvento())){
							dem.setFechaEvento(d);
							dem.getEventoInfo().setFechaFinValidez(d);
							break;
						}
					}
				}
			}
		}
		
		//ICO-70684 Quitamos las demoras nuevas generadas anteriores a la fecha del recálculo, ya que no se tienen que generar si se habían borrado manualmente.
		List<EventoAutomatico> nuevosEventosDemora = new ArrayList<>();
		
		for (EventoAutomatico eventoDemora : demoras) {
			if(FechaUtils.truncateDate(eventoDemora.getFechaEvento()).compareTo(FechaUtils.truncateDate(FechaUtils.restaDia(fechaInicioEjecucionEventos))) >= 0) {
				nuevosEventosDemora.add(eventoDemora);
			}
		}
		System.out.println("=== Demoras finales antes de return ===");
		for (EventoAutomatico d : nuevosEventosDemora) {
		    System.out.println("Demora: " + d.getFechaInicio() + " -> " + d.getFechaEvento() + " | Parciales: " +
		        (d.getEventosParciales() != null ? d.getEventosParciales().size() : 0));
		    if (d.getEventosParciales() != null) {
		        for (EventoAutomatico p : d.getEventosParciales()) {
		            System.out.println("  Parcial: fechaEvento=" + p.getFechaEvento() + " importe=" + p.getImporte().getCantidad());
		        }
		    }
		}
		
		
		return nuevosEventosDemora;	
		//Fin ICO-70684
	}
	
	/**
	 *  Algoritmo de calculo de enventos para banca ICO
	 */
	public List<EventoAutomatico> doEventosICD(Date fechaInicioEjecucionEventos, SaldosTotalesOp saldos,
			EventosOperacion eventosOperacion, List<Date> festivos, Map<Date, List<Cobro>> cobros)
			throws PlanEventoException {
		
 		SaldosTotalesOp saldosTotalesAux = null;
		try {
			saldosTotalesAux = (SaldosTotalesOp)saldos.clone();
		} catch (CloneNotSupportedException e1) {
			LOG.info(this.getClass().getName()+ ".doEventos Error al hacer saldos.clone() ", e1);
		}

		List<Date> calendarioDemoras = null;
		List <Date> fechasAVAS = null;
		//-------------> Obtencion del calendario de vencimientos.
		/**
		 * Primero obtenemos el calendario completo de demoras. La segunda fecha
		 * obtenida del calendario, es la primera de la fecha de liquidación de
		 * demoras, ya que, la primera fecha del calendario, establece el inicio
		 * del periodo de cálculo de la primera liquidación.
		 */

		if(!this.isVariable() && eventosOperacion.getDemorasAnteriores() != null) { 
			Date fechaUltimaLiquidacion = null;
			for(EventoAutomatico ea : eventosOperacion.getDemorasAnteriores()) {
				if(fechaUltimaLiquidacion == null) {
					fechaUltimaLiquidacion = ea.getFechaEvento();
				}
				else {
					if(fechaUltimaLiquidacion.before(ea.getFechaEvento())) {
						fechaUltimaLiquidacion = ea.getFechaEvento();
					}
				}
			}
			if(fechaUltimaLiquidacion != null && fechaUltimaLiquidacion.before(fechaInicioEjecucionEventos)) {
				fechaInicioEjecucionEventos = fechaUltimaLiquidacion;
			}
		}

		try {
			
			List<Evento> liquidacionesManuales = eventosOperacion.getLiquidacionInteresesManuales();
			
			Set<TipoInteresFijado> tiposInteresAplicables = this.getPlanInteresPorDefectoVigente().getTipoInteres();
			
			Date fechaLimite = fechaFinLiquidacionesPorTipoInteres(tiposInteresAplicables);
			//ICO-73341 tener en cuenta la fecha fin tipos en FAD
			List <Date> vencimientos = getCalendarioVencimientosNew(fechaLimite);			
			
			List<Evento> amortizaciones = eventosOperacion.getAmortizaciones();	
			
			fechasAVAS=obtenerAmortizacionesVoluntarias (amortizaciones);
			
			Evento liquidacionAnterior=eventosOperacion.getUltimaDemora(fechaInicioEjecucionEventos, vencimientos);
			

//			Date fechaLimite = this.getOperacion().getFechaFinOperacion();
			
			calendarioDemoras = getCalendarioVencimientosIntereses(fechaInicioEjecucionEventos, fechaLimite, liquidacionesManuales, liquidacionAnterior, festivos);
			

		} catch (POJOValidationException e) {
			throw new PlanEventoException(e.getCause()+"Error al construir el calendario de vencimientos");
		}

		System.out.println("=== CALENDARIO DEMORAS ===");
		for (Date d : calendarioDemoras) {
		    System.out.println("Fecha calendario: " + d);
		}
		List<EventoAutomatico> demoras = calcularDemoras(calendarioDemoras, saldosTotalesAux, festivos, fechasAVAS, cobros);

		/**
		 * Generamos demoras sobre demoras.
		 * Las demoras sobre demoras se asociaran como liquidaciones hijas
		 * por el concepto de resto a las liquidaciones padre.
		 */

		if (getConceptosDemora().contains(ConceptoDemoraEnum.DEMORAS.getCodigo())) {

			if(eventosOperacion.getDemorasAnteriores()!=null){
				demoras.addAll(eventosOperacion.getDemorasAnteriores());
				Collections.sort(demoras, new EventoFechaEventoComparator());
			}

			if (demoras.size()>0) {
				//Si vengo de un cobro, resto un día
				if(eventosOperacion.isCobro()) {
					fechaInicioEjecucionEventos = FechaUtils.restaDia(fechaInicioEjecucionEventos);
				}
				UtilsCalculoDemoraSobreDemora.calcularDemoraSobreDemora(this,fechaInicioEjecucionEventos, demoras, saldosTotalesAux,
						eventosOperacion, festivos, calendarioDemoras, cobros );
			}
		}

		if(eventosOperacion.getDemorasAnteriores()!=null){
			for (EventoAutomatico demora: eventosOperacion.getDemorasAnteriores()){
				demoras.remove(demora);
			}
		}

		//redondear ahora
		for(EventoAutomatico ev: demoras){
			try {
				//ICO - 12278 Cuando la moneda esté dentro del enum con el valor a true por ejemplo la moneda JPY ( Yenes japoneses ) se le realizará al importe de la demoras un redondeo.
				if(EnumRedondeoMoneda.esRedondeable(this.getOperacion().getDivisaOperacion().getCodigo().intValue())){
					ev.getImporte().setCantidad(FormatUtils.redondear(ev.getImporte().getCantidad(), 0));
				}else{
					ev.getImporte().setCantidad(FormatUtils.redondear(ev.getImporte().getCantidad(), 2));
				}
			} catch (Exception e) {
				throw new PlanEventoException(new POJOValidationMessage("Se ha producido un error redondeando el importe"));
			}
		}

		List<EventoAutomatico> listasDemoras = eventosOperacion.getDemorasAnteriores();
		if (listasDemoras!=null){
			Iterator<EventoAutomatico> it = listasDemoras.iterator();
			while(it.hasNext()) {
				EventoAutomatico ev = it.next();
				for (int i=0; i<demoras.size(); i++){
					if( FechaUtils.truncateDate(ev.getFechaEvento()).compareTo(FechaUtils.truncateDate(demoras.get(i).getFechaEvento()))==0 ) {
							demoras.remove(i);
					}	
				}						
			}
		}
				
		return demoras;
		
	}

	protected List<EventoAutomatico> calcularDemoras(List<Date> calendarioDemoras,
			SaldosTotalesOp saldos, List<Date> festivos, List<Date> fechaAVAS, Map<Date, List<Cobro>> cobros) throws PlanEventoException {

		//-------------> Obtencion de datos por el concepto capital.

		List<EventoAutomatico> liquidacionesCapital = new ArrayList<EventoAutomatico>();
		List<CantidadTramo> saldosAux = new ArrayList<>(); // ICO-62994
		
		if (getConceptosDemora().contains(ConceptoDemoraEnum.CAPITAL.getCodigo())&&calendarioDemoras.size()>0) {

			/**
			 * Se obtienen los tipos de interes por el concepto capital.
			 * Cuando el prestamo no tiene plan de amortizacion frances y
			 * el concepto es capital, los tipos se obtienen restando los tipos
			 * del interes de la demora con los de la operación.
			 * Los tipos de entrada son ordenados dentro del método, los
			 * tipos de salida son devueltos sin ordenar.
			 */

			Set<TipoInteresFijado> tiposIntCapital = obtenerTiposParaCapital(getPlanInteresPorDefectoVigente().getTipoInteres()); 
			
			/**
			 * Se tratan los tipos de interes porcentuales-absolutos
			 * por el concepto capital. Se obtendrá una única linea
			 * temporal de tipos de interés.
			 * Los tipos devueltos estan ordenados por fechas.
			 */
			// INI -ICO-35719 - 12-02-2015 -Se calcula la demora teniendo en cuenta el margen y el tipo de interés de demora
			if (getOperacion().getPlanAmortizacion().isPlanAmortizacionFrances() || (getOperacion().getTipoOperacionActivo().equals(TipoOperacionActivoEnum.EL))
					|| getOperacion().getCalculoEspecial()) { //si es frances se aplica tipo de demora
				tiposIntCapital = getPlanInteresPorDefectoVigente().obtenerTiposTratados(tiposIntCapital);
//				Añadido para BANCAICO
//				if(getOperacion().getTipoOperacionActivo().equals(TipoOperacionActivoEnum.ICD)){
//					tiposIntCapital = getPlanInteresPorDefectoVigente().asignarUltimoTipoParaFuturasFijaciones(calendarioDemoras,
//							tiposIntCapital);
//				}
				
			}else { //sino es frances aplica el margen
				tiposIntCapital = getPlanInteresPorDefectoVigente().obtenerTiposTratadosDemoraCapital(tiposIntCapital);
			}		
			// FIN -ICO-35719 - 12-02-2015

			/**
			 * Se homologan los tipos segun las base de calculo de
			 * Demora y Operacion
			 */
	//		tiposIntCapital = homologarBaseCalculoTiposDemora(tiposIntCapital,
	//				getPlanInteresPorDefectoVigente().getBaseCalculo(), baseOperacion);
			/**
			 * Obtener saldos por capital. Se obtiene una lista de objetos auxiliares
			 * con los importes aplicados entre dos fechas, ya que, los saldos son
			 * solo a una fecha y se necesitan cálculos por periodos.
			 */
			List<CantidadTramo> saldosCapital = getSaldosCapital(saldos, calendarioDemoras, fechaAVAS);
			/**
			 * Obtener liquidaciones parciales calculadas capital.
			 *
			 */
			
			// INI ICO-62994
			if(this.getOperacion() instanceof OperacionFD && !esCarteraTraspasada(this.getOperacion())) {
				
				saldosAux = getSaldosABorrar(saldosCapital, calendarioDemoras, festivos, saldos, Boolean.TRUE, cobros);
				
				if(!saldosCapital.isEmpty() && !saldosAux.isEmpty()) {
					saldosCapital.removeAll(saldosAux);
				}
			}
			// FIN ICO-62994
			
			liquidacionesCapital.addAll(getPlanInteresPorDefectoVigente().doEventos(LiqDemorasImp.class, saldosCapital,
					calendarioDemoras, tiposIntCapital, festivos));
			
			System.out.println("=== Parciales Capital generados ===");
			for (EventoAutomatico ev : liquidacionesCapital) {
			    System.out.println("Capital: fechaEvento=" + ev.getFechaEvento() + " importe=" + ev.getImporte().getCantidad());
			}
		}

		//-------------> Obtencion de datos por el resto de conceptos.
		List<CantidadTramo> saldosResto = null;
		List<EventoAutomatico> liquidacionesResto = new ArrayList<EventoAutomatico>();
		List<EventoAutomatico> liquidacionesResto2 = liquidacionesResto;
		if (hayConceptosResto() && !calendarioDemoras.isEmpty()) {
			/**
			 * Se obtienen los tipos de interes por el concepto resto.
			 * Los tipos de interes son los tipos de interes demora.
			 */
			Set<TipoInteresFijado> tiposIntResto = obtenerTiposParaResto(getPlanInteresPorDefectoVigente().getTipoInteres());
			/**
			 * Se tratan los tipos de interes porcentuales-absolutos
			 * por el concepto resto.
			 *
			 */
			tiposIntResto = getPlanInteresPorDefectoVigente().obtenerTiposTratados(tiposIntResto);

			/**
			 * Se homologan los tipos segun las base de calculo de
			 * Demora y Operacion
			 */
	//		tiposIntResto = homologarBaseCalculoTiposDemora(tiposIntResto,
	//				getPlanInteresPorDefectoVigente().getBaseCalculo(), baseOperacion);

			/**
			 * Obtener saldos del resto de conceptos.
			 * Los saldos de resto, son fusionados en una única línea de objetos
			 * auxiliares de cálculo, en función de los conceptos seleccionados.
			 * Por ejemplo, si se ha seleccionado como concepto intereses y gastos
			 * suplidos, se toman los saldos que aplican de cada uno de los conceptos
			 * y se fusionan.
			 */
			try {
				saldosResto = obtenerSaldosParaResto(saldos, getConceptosDemoraResto(), calendarioDemoras, fechaAVAS);
			} catch (Exception e) {
				throw new PlanEventoException(e.getCause()+"Error al obtener "
						+"y fusionar saldos del resto de conceptos"); 
			}
			/**
			 * Obtener liquidaciones parciales calculadas resto.
			 *
			 */
			
			// INI ICO-62994
			if(this.getOperacion() instanceof OperacionFD && !esCarteraTraspasada(this.getOperacion())) {
				saldosAux = getSaldosABorrar(saldosResto, calendarioDemoras, festivos, saldos, Boolean.FALSE, cobros);
				
				if(!saldosResto.isEmpty() && !saldosAux.isEmpty()) {
					saldosResto.removeAll(saldosAux);
				}
			}
			// FIN ICO-62994
			
			liquidacionesResto2.addAll(getPlanInteresPorDefectoVigente().doEventos(LiqDemorasImp.class, saldosResto,
					calendarioDemoras, tiposIntResto, festivos));
			System.out.println("=== Parciales Resto generados ===");
			for (EventoAutomatico ev : liquidacionesResto2) {
			    System.out.println("Resto: fechaEvento=" + ev.getFechaEvento() + " importe=" + ev.getImporte().getCantidad());
			}
		}

		//-------------> Reagrupamiento por fechas de vencimiento.
		/**
		 * Obtener liquidaciones padres agrupadas por calendario de
		 * vencimiento. Se obtiene una liquidación padre en cada periodo
		 * de liquidación de demoras, que contiene liquidaciones parciales
		 * de resto y de capital, cada uno formada por el periodo en el
		 * que los saldos y los tipos de interés son homogeneos.
		 * Obtener fechas de vencimiento ajustada y de mora.
		 * Asociar al plan los eventos.
		 */
		try {
			List<EventoAutomatico> demoras = agruparDemoras(calendarioDemoras, liquidacionesCapital, liquidacionesResto2, festivos);
			return demoras;
		} catch (POJOValidationException e) {
			throw new PlanEventoException(e.getCause()+" Error al agrupar demoras de resto y capital");
		}
	}
	
	// INI ICO-62994
	private Boolean esCarteraTraspasada(Operacion op) {
		Boolean esCarteraTraspasada = false;
		
		if (op instanceof OperacionFD && op.getCodigoHost() != null &&
				(op.getCodigoHost().startsWith("1518") ||
				 op.getCodigoHost().startsWith("1519") ||
				 op.getCodigoHost().startsWith("1520"))) {
			esCarteraTraspasada = true;
		}
		
		return esCarteraTraspasada;
	}
	
	private List<CantidadTramo> getSaldosABorrar(List<CantidadTramo> tramoSaldos, List<Date> calendarioDemoras, List<Date> festivos, SaldosTotalesOp saldosTotales, Boolean esCapital, Map<Date, List<Cobro>> cobros){
		List<CantidadTramo> saldosAux = new ArrayList<>();
		Calendar hoy = Calendar.getInstance();
		hoy.set(Calendar.HOUR_OF_DAY, 0);
		hoy.set(Calendar.MINUTE, 0);
		hoy.set(Calendar.SECOND, 0);
		hoy.set(Calendar.MILLISECOND, 0);
		
		Calendar fechaAuxIni = Calendar.getInstance();
		Calendar fechaAuxFin = Calendar.getInstance();
		Calendar fechaAuxIniAjustada = Calendar.getInstance();
		Calendar fechaAuxFinAjustada = Calendar.getInstance();
		Date fechaFinActualizada;
		BigDecimal importeCobros;
		CantidadTramo saldoNew = null;
		
		for(CantidadTramo saldoAux : tramoSaldos) {
			fechaAuxIni.setTime(saldoAux.getfechaIni());
			fechaAuxFin.setTime(saldoAux.getfechaFin());
			
			try {
				fechaAuxIniAjustada.setTime(getFechaParaPago(getOperacion().getPlanAjustableDias(), fechaAuxIni.getTime(), festivos, this.getClass(), false));
				fechaAuxFinAjustada.setTime(getFechaParaPago(getOperacion().getPlanAjustableDias(), fechaAuxFin.getTime(), festivos, this.getClass(), false));
			} catch (PlanEventoException e) {
				LOG.error( PlanDemoraImp.class.getName() +
						" getSaldosABorrar "  +
						e.getMessage() + ". " + e.getStackTrace()[0]);
				
				fechaAuxIniAjustada.setTime(fechaAuxIni.getTime());
				fechaAuxFinAjustada.setTime(fechaAuxFin.getTime());
			}
			
			if(getOperacion().getPlanAjustableDias().getPeriodoDeGracia() != null) {
				fechaAuxIniAjustada.add(Calendar.DAY_OF_YEAR, getOperacion().getPlanAjustableDias().getPeriodoDeGracia());
				fechaAuxFinAjustada.add(Calendar.DAY_OF_YEAR, getOperacion().getPlanAjustableDias().getPeriodoDeGracia());
			}
			
			if(!hoy.after(fechaAuxIniAjustada)) { //Si el tramo ajustado empieza a futuro, no hay que tenerlo en cuenta, por lo que se elimina
				saldosAux.add(saldoAux);
			} else {
				if(!hoy.after(fechaAuxFinAjustada) && hayMoraEnSaldo(saldosTotales.getSaldosOp(fechaAuxFin.getTime()), esCapital)) { //Si el tramo ajustado finaliza a futuro, hay que ampliarlo hasta el final de este vencimiento, porque hay que asumir que no hay ningun cambio
					fechaFinActualizada = getFechaFinActualizada(calendarioDemoras, fechaAuxFin);
					
					importeCobros = getImporteCobrosAVencimiento(cobros, fechaAuxFin, esCapital);
					
					if (fechaFinActualizada != null) {
						if(importeCobros.compareTo(BigDecimal.ZERO) == 0) {
							saldoAux.setfechaFin(fechaFinActualizada);
						} else {
							saldoNew = new CantidadTramo(fechaAuxFin.getTime(), fechaFinActualizada, saldoAux.getCantidad().subtract(importeCobros));
						}
					}
				}
			}
		}
		
		if(saldoNew != null) {
			tramoSaldos.add(saldoNew);
		}
		
		return saldosAux;
	}
	
	private Date getFechaFinActualizada(List<Date> calendarioDemoras, Calendar fechaFin) {
		Date fechaFinAux = null;
		
		for(Date fechaDem : calendarioDemoras) {
			if(fechaFin.getTime().compareTo(fechaDem) < 0) {
				fechaFinAux = fechaDem;
				break;
			}
		}
		
		return fechaFinAux;
	}
	
	private Boolean hayMoraEnSaldo(SaldosOp saldo, Boolean esCapital) {
		Boolean hayMora = false;
		BigDecimal importe = BigDecimal.ZERO;
		
		if(saldo != null) {
			if(esCapital) {
				hayMora = saldo.getSaldoMoraAmortizacion().compareTo(BigDecimal.ZERO) > 0;
			} else {
				for (String concepto : getConceptosDemoraResto()) {
					//obtienen los eventos del concepto correspondiente
					if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.INTERESES)
						importe = importe.add(saldo.getSaldoMoraInteres());
					else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.COMIS_APER_EST_ASEG)
						importe = importe.add(saldo.getSaldoMoraComisionAperEstAseg());
					else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.GASTOS_SUPLIDOS)
						importe = importe.add(saldo.getSaldoMoraComisionGastosSuplidos());
					else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.COMISION_AGENCIA)
						importe = importe.add(saldo.getSaldoMoraComisionAgencia());
					else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.RESTO_COMSIONES)
						importe = importe.add(saldo.getSaldoMoraComisionResto());
				}
				
				hayMora = importe.compareTo(BigDecimal.ZERO) > 0;
			}
		}
		
		return hayMora;
	}
	
	private BigDecimal getImporteCobrosAVencimiento(Map<Date, List<Cobro>> cobros, Calendar fechaVencimiento, Boolean esCapital) {
		BigDecimal importeCobros = BigDecimal.ZERO;
		
		if(cobros!=null && cobros.size()>0){ //ICO-37649 (para tener en cuenta los cobros de demora)
			
			Collection<List<Cobro>> collectionCobros = cobros.values();
			
			for (List<Cobro> listCob : collectionCobros){  //Saco las fechas de los cobros a una lista si son de demoras
				
				for(Cobro cobro : listCob){
					
					if(esCapital) {
						importeCobros = importeCobros.add(getImporteCobroCapitalAVencimiento(cobro, fechaVencimiento));
					} else {
						importeCobros = importeCobros.add(getImporteCobroRestoAVencimiento(cobro, fechaVencimiento));
					}
				}
			}
			
		}
		
		return importeCobros;
	}
	
	private BigDecimal getImporteCobroCapitalAVencimiento(Cobro cobro, Calendar fechaVencimiento) {
		BigDecimal importe = BigDecimal.ZERO;
		
		if(cobro.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_AMORTIZACION.getCodigo()) && cobro.getFechaCobro().compareTo(fechaVencimiento.getTime()) == 0){
			importe = importe.add(cobro.getImporte().getCantidad());
		}
		
		return importe;
	}
	
	private BigDecimal getImporteCobroRestoAVencimiento(Cobro cobro, Calendar fechaVencimiento) {
		BigDecimal importe = BigDecimal.ZERO;
		
		for (String concepto : getConceptosDemoraResto()) {
			//obtienen los eventos del concepto correspondiente
			if ((ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.INTERESES &&
					cobro.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_INTERESES.getCodigo()) && cobro.getFechaCobro().compareTo(fechaVencimiento.getTime()) == 0)
				||
				((ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.COMIS_APER_EST_ASEG || ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.RESTO_COMSIONES) &&
					cobro.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_COMISIONES.getCodigo()) && cobro.getFechaCobro().compareTo(fechaVencimiento.getTime()) == 0)
				||
				(ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.GASTOS_SUPLIDOS &&
					cobro.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_GASTOS_SUPLIDOS.getCodigo()) && cobro.getFechaCobro().compareTo(fechaVencimiento.getTime()) == 0)
				||
				(ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.COMISION_AGENCIA &&
				cobro.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_COMISION_AGENCIA.getCodigo()) && cobro.getFechaCobro().compareTo(fechaVencimiento.getTime()) == 0)){
					importe = importe.add(cobro.getImporte().getCantidad());
			}
		}
		
		return importe;
	}
	// Fin ICO-62994

	/**
	 * Metodo que devuelve el importe calculado con el algoritmo de liquidacion
	 * de intereses
	 *
	 * ALGORITMO IMPORTE DE LA LIQUIDACION capital*interes*periodo)/(100*base)
	 * ===> baseconjunta = periodo_basecalculo/base_basecalculo
	 *
	 * @param capital -
	 *            capital de calculo (por ejemplo: interes vencidos + amortizaciones vencidas en la
	 *            operacion)
	 * @param interes -
	 *            interes a aplicar (por ejemplo: interes nominal de la
	 *            operacion)
	 * @param base -
	 *            base calculo con numerador y denominador	
	 * @return
	 * @throws Exception 
	 */
	//INI - ICO-34295 - 10-12-2014
	public BigDecimal obtenerImporteLiquidacionDemoras(BigDecimal interesVencidos,BigDecimal amortizacionesVencidas, BigDecimal interes, BigDecimal base, String conceptoDemora)  {

		/* FM (26/11/2011): Se cambia la rutin	a de redondeo por problemas con el redondeo de decimales*/
		//return new BigDecimal((capital.multiply(interes).multiply(base)).doubleValue() / 100).setScale(5, BigDecimal.ROUND_UP);		
		//BigDecimal aux= new BigDecimal ((capital.multiply(interes).multiply(base)).doubleValue() / 100);
		BigDecimal aux = BigDecimal.ZERO;
		if (("CAPITAL").equals(conceptoDemora)){
			aux=( (amortizacionesVencidas.multiply(interes).multiply(base)).divide(new BigDecimal(100),10,RoundingMode.HALF_UP));
		}else{
			aux=( (interesVencidos.multiply(interes).multiply(base)).divide(new BigDecimal(100),10,RoundingMode.HALF_UP));
		}

		try {
			if(!(this.getOperacion() instanceof OperacionBN)) {
				aux=FormatUtils.redondear(aux, 2);
			}
		} catch (Exception e) {
			try {
				aux=FormatUtils.redondear(aux, 2);
			} catch (Exception e1) {
				LOG.error(getClass().getName() + "obtenerImporteLiquidacionDemoras(..):" + e.getMessage(), e);
			}
		}
		return aux;
		
	}
	//FIN - ICO-34295 - 10-12-2014
	
	protected List<CantidadTramo> getSaldosCapital(SaldosTotalesOp saldos, List<Date> calendarioDemoras, List<Date> fechaAVAS) {

		List<CantidadTramo> saldosMora = new ArrayList<CantidadTramo>();
		HashMap<Date, BigDecimal> saldosFecha = new HashMap<Date, BigDecimal>();
		Date fechaHasta=calendarioDemoras.get((calendarioDemoras.size()-1));		
		boolean parciales=false;
		BigDecimal importeVcto=BigDecimal.ZERO;
		Date fechaAnt = null; //ICO-62994
		
		for(SaldosOp saldosOp : saldos.getSaldosOperacion()) {
			Date fechaInicioTramo = saldosOp.getFechaSaldo();
			BigDecimal importe = BigDecimal.ZERO;

			for (String concepto : conceptosDemora) {
				//obtienen los eventos del concepto correspondiente
				if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.LIQUIDACIONES_PARCIALES){
					//importe = importe.add(saldosOp.getSaldoMoraAmortizacion());
					parciales=true;
				}
				else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.CAPITAL)
					importe = importe.add(saldosOp.getSaldoMoraAmortizacion());
				else
					continue;
			}	
			
			if (calendarioDemoras.contains(fechaInicioTramo)){  
				importeVcto=saldosOp.getSaldoAmortizacionesV();
			}
			
			if( fechaAVAS.contains(fechaInicioTramo) && calendarioDemoras.contains(fechaInicioTramo)) { //si AVA no meter para calcular demora
				saldosFecha.put(fechaInicioTramo, importe);			
			} else  if (fechaAVAS.contains(fechaInicioTramo) && !calendarioDemoras.contains(fechaInicioTramo) && this.getOperacion() instanceof OperacionEL) { // no meter a no ser que tenga concepto de parciales
				if (parciales){
					saldosFecha.put(fechaInicioTramo, importe);
				}else {
					if(saldosOp.getSaldoTotalVC().compareTo(BigDecimal.ZERO)==1){ //si tvc es mayor que cero significa hay un cobro por lo que meto 
						if (importeVcto.subtract(saldosOp.getSaldoAmortizacionesVC()).compareTo(BigDecimal.ZERO)<=0){
							saldosFecha.put(fechaInicioTramo, BigDecimal.ZERO); 
						}else {
							saldosFecha.put(fechaInicioTramo, importeVcto.subtract(saldosOp.getSaldoAmortizacionesVC())); 
						}
					}else { 
						break;
					}
				}
			} else if (!calendarioDemoras.contains(fechaInicioTramo) && this.getOperacion() instanceof OperacionEL) { 
				//como no entro en los if anteriores significa que tiene que ser un cobro, no es ava, ni vcto, por lo que es cobro
				if (parciales){
					saldosFecha.put(fechaInicioTramo, importe);
				}else {
						if (importeVcto.subtract(saldosOp.getSaldoAmortizacionesVC()).compareTo(BigDecimal.ZERO)<=0){
							saldosFecha.put(fechaInicioTramo, BigDecimal.ZERO); 
						}else {
							saldosFecha.put(fechaInicioTramo, importeVcto.subtract(saldosOp.getSaldoAmortizacionesVC())); 
						}
				}
			
			} else {
				saldosFecha.put(fechaInicioTramo, importe);
			}

		}
		
		//crea los tramos de saldo en funcion de los saldos obtenidos
		List<Date> fechasSaldo = new ArrayList<>(saldosFecha.keySet());
		Collections.sort(fechasSaldo);
		
		CantidadTramo tramo = null;
		for(Date fecha : fechasSaldo) {
			if(tramo != null)
				tramo.setfechaFin(fecha);
			tramo = new CantidadTramo(fecha, null, saldosFecha.get(fecha)) ;
			if(tramo.getCantidad().compareTo(BigDecimal.ZERO)>0)
				saldosMora.add(tramo);
		}
		if(tramo != null)
			tramo.setfechaFin(fechaHasta);

		return saldosMora;
	}

	/**
	 * Metodo que comprueba si el plan tiene algun concepto de los considerados
	 * de resto. No se consideran de resto ni capital ni demoras.
	 */
	protected Boolean hayConceptosResto() {
		if ((getConceptosDemora().contains(ConceptoDemoraEnum.COMIS_APER_EST_ASEG.getCodigo()))
				||(getConceptosDemora().contains(ConceptoDemoraEnum.GASTOS_SUPLIDOS.getCodigo()))
				||(getConceptosDemora().contains(ConceptoDemoraEnum.INTERESES.getCodigo()))
				||(getConceptosDemora().contains(ConceptoDemoraEnum.COMISION_AGENCIA.getCodigo()))
				||(getConceptosDemora().contains(ConceptoDemoraEnum.RESTO_COMSIONES.getCodigo()))
				||(getConceptosDemora().contains(ConceptoDemoraEnum.CUOTA_CLIENTE.getCodigo()))) {
			return true;
		}
		return false;
	}

	/**
	 * Método que devuelve todas las fechas en las que se tiene que liquidar
	 * demoras.
	 * Se obtienen a partir de todas las fechas de los eventos demorables.
	 * Se añaden además todas las fechas del calendario de liquidacion de intereses.
	 * Se ordenan las fechas.
	 *
	 * @author salonso
	 * @return calDemoras Set<Date>
	 * @throws POJOValidationException
	 */
	@Override
	public List<Date> getCalendarioVencimientos(EventosOperacion eventosPlanes, List<Date> festivos, PlanAjustableDias planAjustableDias) throws POJOValidationException { //ICO-56590
		throw new RuntimeException(new NoSuchMethodException("WORKAROUND. Este método ya no es válido. " +
				"Usar getCalendarioVencimiento(EventosOperacion , EventosOperacionJDBC )"));
	}

	public List<Date> getCalendarioVencimientos(Date fechaInicioEjecucionEventos, EventosOperacion eventosPlanes,
			SaldosTotalesOp saldos,  List<Date> festivos) throws POJOValidationException {
		//Date fechaPrimerVencDemPorEvento = null;
		Date fechaPrimerVencDemPorIntereses = null;
		List<Date> calendarioDemoras = new ArrayList<Date>();
		Boolean finMes = getOperacion().getPlanAjustableDias().getPagableFinMes();

		List<Date> calendarioIntereses = getOperacion().getPlanInteres().getCalendarioVencimientosCalculado(
				fechaInicioEjecucionEventos, festivos, eventosPlanes);

		//fecha de final de tipo de interés
		Date fechaFin = fechaFinLiquidacionesPorTipoInteres(getPlanInteresPorDefectoVigente().getTipoInteres());
		/*
		 * Si no hay tipos de interés, no se van a generar demoras, por lo que
		 * el calendario devuelto debe ser vacio.
		 */
		if (fechaFin==null)
			return calendarioDemoras;

		/*
		 * fechas de vencimiento de los eventos demorables.
		 */
		if (getConceptosDemora().size()>0) {
			List<ConceptoDemoraEnum> conceptosDemora = new ArrayList<ConceptoDemoraEnum>();
			for (String concepto : getConceptosDemora()) {
				conceptosDemora.add(ConceptoDemoraEnum.getEnumByCode(concepto));
			}
			//Nos quedamos con la anterior no nula del resultado de los dos métodos
			//try{
				//fechaPrimerVencDemPorEvento = eventosOperacionJDBC.getFechaPrimerEventoDemorable(getOperacion().getId(), conceptosDemora);
			if ( fechaPrimerVencDemPorEvento == null){
				//lo buscamos entre los eventos generados
				fechaPrimerVencDemPorEvento = eventosPlanes.obtenerFechaPrimerEventoDemorable(conceptosDemora);
			}
		}

		/*
		 * fechas de vencimiento de las liquidaciones de interes.
		 * comprubea que existan intereses vencidos
		 */
		if(calendarioIntereses.size()>1 &&
			saldos.getSaldoOperacion(calendarioIntereses.get(1),
					EnumTipoSaldos.SALDO_INTERESES_VENCIDOS).compareTo(BigDecimal.ZERO)>0){
			//este calendario define los tramos de vencimiento, siendo la get(0) la fecha inicio del 1er tramo.
			//Por lo tanto la primera fecha de vencimiento es get(1)
			fechaPrimerVencDemPorIntereses = calendarioIntereses.get(1);
		}

		//----> Construimos el calendario.
		/*
		 * Si ambas fechas son null, no hay nada que generar
		 */
		Date fechaAux = null;
		Date fechaPost = null;
		if (fechaPrimerVencDemPorEvento==null&&fechaPrimerVencDemPorIntereses==null) {
			return calendarioDemoras;
		} else
		/*
		 * Si la fecha de liquidacion de intereses es null, pero no la fecha del
		 * evento susceptible de generar demoras, generamos el calendario en base
		 * solo a los eventos demorables.
		 */
		if (fechaPrimerVencDemPorIntereses==null&&fechaPrimerVencDemPorEvento!=null) {
			fechaAux = fechaPrimerVencDemPorEvento;
			fechaPost = fechaPrimerVencDemPorEvento;
			rellenarCalendarioDemoras(calendarioDemoras, fechaAux, fechaPost, fechaFin, finMes);
		} else
		/*
		 * Si solo hay intereses, generamos el calendario en base
		 * solo a los eventos de intereses.
		 */
		if (fechaPrimerVencDemPorIntereses!=null&&fechaPrimerVencDemPorEvento==null) {
			fechaAux = fechaPrimerVencDemPorIntereses;
			fechaPost = fechaPrimerVencDemPorIntereses;
			//TODO calendarioDemoras=calendarioIntereses (la primera fecha es el inicio del primer tramo de demoras)
			// TODO filtrar por fechaFin
			rellenarCalendarioDemoras(calendarioDemoras, fechaAux, fechaPost, fechaFin, finMes);
		} else {//ambas fechas son no nulas
			/*
			 * Si la fecha de liquidacion de intereses es menor a la fecha de demora
			 * del primer evento susceptible de generar demoras, se retorna lo mismo
			 * que en el caso anterior, ya que manda el primer interes.
			 */
			if (fechaPrimerVencDemPorIntereses.compareTo(fechaPrimerVencDemPorEvento)<=0) {
				fechaAux = fechaPrimerVencDemPorIntereses;
				fechaPost = fechaPrimerVencDemPorIntereses;
				rellenarCalendarioDemoras(calendarioDemoras, fechaAux, fechaPost, fechaFin, finMes);
			}
			/*
			 * Si la fecha de demora del primer evento susceptible de generar demoras,
			 * es anterior a la de la primera liquidación de intereses, se construye
			 * el calendario a partir de dicho evento hasta encontrarse una liquidación
			 * de intereses.
			 */
			if (fechaPrimerVencDemPorEvento.compareTo(fechaPrimerVencDemPorIntereses)<0) {

				fechaAux = fechaPrimerVencDemPorEvento;
				while (fechaAux.compareTo(fechaPrimerVencDemPorIntereses)<0) {
					calendarioDemoras.add(fechaAux);
					fechaAux = getPlanInteresPorDefectoVigente().getPeriodicidadLiquidacion().aplicarPeriodicidad(
							fechaPrimerVencDemPorEvento, fechaAux);

					if (finMes)
						fechaAux = getFechaParaFinMes(fechaAux);
				}
				if (fechaFin.compareTo(fechaPrimerVencDemPorIntereses)<=0) {
					calendarioDemoras.add(fechaPrimerVencDemPorIntereses);
				} else {
					//TODO añadir el resto de fechas que aplican del calendarioIntereses
					fechaAux = fechaPrimerVencDemPorIntereses;
					fechaPost = fechaPrimerVencDemPorIntereses;
					rellenarCalendarioDemoras(calendarioDemoras, fechaAux, fechaPost, fechaFin, finMes);
				}
			}
		}
		/*
		 * Finalmente, siempre que se produzca un evento de liquidación de intereses (tanto automático como manual)
		 * en la fecha del evento debe producirse un vencimiento de demoras. Además entre 2 eventos de liquidación de intereses se
		 * producirán tantos vencimientos de demora como sea posible aplicando la periodicidad de la demora.
		 */

		calendarioDemoras = getCalendarioAjustado(calendarioDemoras, getOperacion().getPlanAjustableDias(),
				festivos);

		for(Evento liqui:eventosPlanes.getLiquidacionIntereses()){

			//Solo deben considerarse las liquidacionesautomaticas totales
			if (liqui instanceof LiquidacionInteresesAutomatica){
				LiquidacionInteresesAutomatica liquiAuto=(LiquidacionInteresesAutomatica)liqui;
				if(liquiAuto.getEventoTotal()!=null){
					continue;
				}
			}

			if(!calendarioDemoras.contains(FechaUtils.truncateDate(liqui.getFechaEvento()))){
				calendarioDemoras.add(FechaUtils.truncateDate(liqui.getFechaEvento()));
			}
		}
		Collections.sort(calendarioDemoras);

		return calendarioDemoras;
	}


    public List<Date> getCalendarioVencimientosIntereses (Date fechaInicioEjecucionEventos, Date fechaFin,
    			List<Evento> liquidacionesManuales, Evento ultimaLiquidacion, List<Date> festivos) throws PlanEventoException {

    		PeriodicidadEnum periodicidad = getPlanInteresPorDefectoVigente().getPeriodicidadLiquidacion();
    		PeriodicidadEnum periodicidadAmortizacion = getOperacion().getPlanAmortizacion().getPeriodicidadAmortizacion();

    		List<Date> fechasVencimiento = new ArrayList<Date>();
    		Date fechaIni = null;
    		Date fechaAux = null;
    		Boolean liquidacionFrances = null;
    		//Se obtiene la fecha de la primera liquidacion
    		if(ultimaLiquidacion != null) {
    			if(ultimaLiquidacion instanceof LiquidacionInteresesAutomaticaOperacionImp) {
    				LiquidacionInteresesAutomaticaOperacion liq = (LiquidacionInteresesAutomaticaOperacionImp)ultimaLiquidacion;
    				if(liq.getEventoTotal() != null) {
    					fechaIni = !liq.getSubTipoEvento().equals(
    									SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION)
    							? liq.getEventoTotal().getFechaEvento() : getPlanInteresPorDefectoVigente().getFechaPrimerEvento();
    					fechaAux = fechaIni;
    					liquidacionFrances = false;
    				}
    				else {
    					fechaIni = !ultimaLiquidacion.getSubTipoEvento().equals(
    									SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION)
    							? ultimaLiquidacion.getFechaEvento() : getPlanInteresPorDefectoVigente().getFechaPrimerEvento();
    					fechaAux = fechaIni;
    					liquidacionFrances = false;
    				}
    			}
    			else {
    				fechaIni = !ultimaLiquidacion.getSubTipoEvento().equals(
    								SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION)
    						? ultimaLiquidacion.getFechaEvento() : getPlanInteresPorDefectoVigente().getFechaPrimerEvento();
    				fechaAux = fechaIni;
    				liquidacionFrances = false;
    			}
    		}
    		else {
    			fechaIni = getOperacion().getPlanInteres().getPlanInteresPorDefectoVigente().getFechaPrimerEvento();
    			fechaAux = fechaIni;
    			liquidacionFrances = false;
    		}

    		//Recupera las liquidaciones manuales
    		Iterator<Evento> itLiqManuales = liquidacionesManuales.iterator();
    		Evento liquidacionNext = itLiqManuales.hasNext() ? itLiqManuales.next() : null;

    		Date primAmort = null;

    		if (fechaFin!=null && !fechaAux.after(fechaFin)) {
    			//Obtiene la fecha de primera amortización si el plan es frances
    			if (this.getOperacion().getPlanAmortizacion().isPlanAmortizacionFrances()) {

    				primAmort = FechaUtils.convertirFecha(this.getOperacion().getPlanAmortizacion().getFechaAmortizacionPrevista());

    				if ((this.getOperacion().getPlanAmortizacion().getAnyosCarencia()!=null)
    						&&(this.getOperacion().getPlanAmortizacion().getAnyosCarencia().compareTo(0)!=0))
    					primAmort = FechaUtils.convertirFecha(this.getOperacion().getPlanAmortizacion().aplicarCarencia());
    			}

    			//Se generan liquidaciones desde la fecha de inicio de ejecucion de eventos
    			//hasta una liquidacion posterior a la fecha de fin de tipo

    			//añade la fecha de formalizacion
    			fechasVencimiento.add(FechaUtils.convertirFecha(getOperacion().getFechaFormalizacion()));

    			//empieza a generar periodicidad desde la fecha de primera liquidacion
    			//a partir del primer periodo que deje sin liquidar una liquidacion manual
    			while(liquidacionNext !=null
    			&& !fechaAux.before(liquidacionNext.getFechaInicio())) {
    				//aplicará periodicidad desde la fecha de la liquidación manual
    				fechaAux = liquidacionNext.getFechaEvento();
    				liquidacionNext = itLiqManuales.hasNext() ? itLiqManuales.next() : null;
    			}
    			//añade la fecha de primera liquidacion
    			fechasVencimiento.add(fechaAux);

    			do {

    				if (!liquidacionFrances) { 
    					
    					//INI ICO-73716
    					if(null != getPlanInteresPorDefectoVigente().getFechaReferencia()) {
              	   			Date fechaReferencia = getPlanInteresPorDefectoVigente().getFechaReferencia();
              	   			Date fechaSiguiente = periodicidad.aplicarPeriodicidad(fechaAux);
              	   			if(fechaReferencia.after(fechaAux) && fechaReferencia.before(fechaSiguiente)) {
              	   				fechaAux = fechaReferencia;
              	   			}
          	   		  	}
    					//FIN ICO-73716
    					
    					//aplica la periodicidad teniendo en cuenta la fecha del primer vencimiento para
    					//que no se aplique periodicidad sobre una fecha ajustada
    					//fechaAux = periodicidad.aplicarPeriodicidad(fechaAux);
						if(this.getOperacion().getPlanInteresDisposicion() == null) {
//							fechaAux=periodicidad.aplicarPeriodicidad(this.getPlanInteresPorDefectoVigente().getFechaPrimerEvento(), fechaAux, periodicidad);
							fechaAux=periodicidad.aplicarPeriodicidad(fechaAux, periodicidad);
						}
						else {
							fechaAux=periodicidad.aplicarPeriodicidad(this.getOperacion().getPlanInteresDisposicion().getPlanInteresPorDefectoVigente().getFechaPrimerEvento(), fechaAux, periodicidad);
						}

    					//añade la fecha de liquidaciones manuales y aplicara periodicidad desde
    					//la ultima en la que quede un tramo sin liquidar
    					while(liquidacionNext !=null
    					&& !fechaAux.before(liquidacionNext.getFechaInicio())) {
    						//aplicará periodicidad desde la fecha de la liquidación manual
    						fechaAux = liquidacionNext.getFechaEvento();
    						liquidacionNext = itLiqManuales.hasNext() ? itLiqManuales.next() : null;
    					}

    					//añade la fecha de inicio de amortizacion del plan frances si la fecha de liquidacion es posterior
    					if ((primAmort!=null)&&(!fechaAux.before(primAmort))) {
    						liquidacionFrances = true;
    						if (primAmort.after(fechaIni)) {
    							fechaAux = primAmort;
    						} else {
    							fechaAux = periodicidadAmortizacion.aplicarPeriodicidad(primAmort, fechaIni);
    						}
    					}
    				} else {
    					//Si es un plan frances aplica periodicidad sobre la fecha de amortizacion una vez alcanzada
    					fechaAux = periodicidadAmortizacion.aplicarPeriodicidad(primAmort, fechaAux);
    				}

    				fechasVencimiento.add(fechaAux);

    			} while (fechaAux.before(fechaFin));
    			/*
    			 * Mantenemos el calendario de las liquidaciones de intereses para ejecuciones posteriores.
    			 */
    			setCalendarioVencimientosCalculado(fechasVencimiento);

    			if(fechaInicioEjecucionEventos != null)
    				fechasVencimiento = getCalendarioVencimientosDesdeFechaPorPeriodos(fechaInicioEjecucionEventos, fechasVencimiento, false); //ICO-67866 Se añade parametro booleano para saber si es prepagable
    			//INI - ICO-12590- 11-06-2014 - Si hay demoras y se recalcula desde una fecha que no sea el incio de de la operación se podían perder algunas demoras
    			//porque el rango de fechasVencimiento comenzaba en la fecha de recalculo y a la primera fecha del rango no le aplicaba getCalendarioAjustado
    			if (fechasVencimiento.size() > 0){
	    			if (getOperacion().getPlanInteres().getPlanInteresPorDefectoVigente().getFechaPrimerEvento().compareTo(fechasVencimiento.get(0)) != 0){
	    				List<Date> fechasVencimientoAuxiliar = new ArrayList<Date>();
	    				fechasVencimientoAuxiliar.add(fechasVencimiento.get(0));
	    				fechasVencimientoAuxiliar=getCalendarioAjustado(fechasVencimientoAuxiliar, getOperacion().getPlanAjustableDias(), festivos);
	    				fechasVencimiento.add(0, fechasVencimientoAuxiliar.get(0));
	    			}
    			}
    			//FIN - ICO-12590 - 11--06-2014

    			fechasVencimiento=getCalendarioAjustado(fechasVencimiento, getOperacion().getPlanAjustableDias(), festivos);

    			//se debe filtrar por las liquidaciones manuales tras el ajuste de fechas
    			fechasVencimiento = filtrarCalendarioPorLiquidacionesManuales(fechasVencimiento, liquidacionesManuales);

    			fechasVencimiento = filtrarCalendarioParaSimulacion(fechasVencimiento);
    		}

    		return fechasVencimiento;

     }

	private List<Date> filtrarCalendarioPorLiquidacionesManuales(List<Date> calendario,
			List<Evento> liquidacionesManuales) {
		// Obtiene un calendario filtrando el calendario de entrada teniendo en cuenta las fechas de los tipos absolutos
		List<Date> calendarioNuevo = new ArrayList<Date>();
		if (calendario!=null&&!calendario.isEmpty()) {
			if (liquidacionesManuales==null||liquidacionesManuales.isEmpty())
				return calendario;

			Iterator<Date> itFechas = calendario.iterator();
			Date fechaUltimaIntroducida = itFechas.next();
			calendarioNuevo.add(fechaUltimaIntroducida); // introducimos la primera fecha del calendario
			Date fechaFinal = calendario.get(calendario.size()-1); // Ultima fecha del calendario
			if (itFechas.hasNext()) {
				Date fecha = itFechas.next();
				Boolean continua = true;
				//				crono.parcial("PlanInteresOperacion:: filtrarCalendarioPorLiquidacionesManuales :: inicio while continua");
				while (continua) {
					//				    	crono.parcial("PlanInteresOperacion:: filtrarCalendarioPorLiquidacionesManuales :: inicio for liquidacionesOrdenadas");
					for (Evento liquidacion : liquidacionesManuales) {
						if(liquidacion.getSubTipoEvento().equals(SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_OPERACION)){
							if (liquidacion.getFechaInicio().before(fecha)) {
								// ignoramos los tipos con fechaInicio posterior a la fecha que estamos tratando
								if (!liquidacion.getFechaInicio().before(fechaUltimaIntroducida)) {
									// tipo absoluto y fechaInicio >= fechaUltimaIntroducida
									if (liquidacion.getFechaInicio().after(fechaUltimaIntroducida)) {
										calendarioNuevo.add(liquidacion.getFechaInicio());
										fechaUltimaIntroducida = liquidacion.getFechaEvento();
									}
									Date fechaInicioTramoSiguiente = liquidacion.getFechaEvento();
									if (fechaInicioTramoSiguiente.before(fechaFinal)) {
										// fechaInicio >= fechaUltimaIntroducida y fechaInicioTramoSiguiente < fechaFinal
										calendarioNuevo.add(fechaInicioTramoSiguiente);
										fechaUltimaIntroducida = fechaInicioTramoSiguiente;
									}
								}
							} else
								break; // como esta en orden, si fechaInicio > fecha salimos del bucle
						}
					}
					//	    				crono.parcial("PlanInteresOperacion:: filtrarCalendarioPorLiquidacionesManuales :: fin for liquidacionesOrdenadas");

					if (fecha.after(fechaUltimaIntroducida)) {
						calendarioNuevo.add(fecha);
						fechaUltimaIntroducida = fecha;
					}
					if (itFechas.hasNext()) {
						fecha = itFechas.next(); // siguiente fecha
						if (!fecha.after(fechaUltimaIntroducida)) // fecha <= fechaUltimaIntroducida
							// ignoramos las fechas del calendario que estan comprendidas entre las fechas
							// de un tramo con tipo absoluto
							//		    			    	crono.parcial("PlanInteresOperacion:: filtrarCalendarioPorLiquidacionesManuales :: inicio while fechas");
							while (itFechas.hasNext()&&!fecha.after(fechaUltimaIntroducida))
								fecha = itFechas.next();
						//		    				crono.parcial("PlanInteresOperacion:: filtrarCalendarioPorLiquidacionesManuales :: fin while fechas");
						if (!fecha.after(fechaUltimaIntroducida))
							continua = false;
						// Se han acabado las fechas
					} else
						continua = false;
				}
				//			crono.parcial("PlanInteresOperacion:: filtrarCalendarioPorLiquidacionesManuales :: fin while continua");
				if (fechaFinal.after(fechaUltimaIntroducida))
					calendarioNuevo.add(fechaFinal);
			}
		}

		return calendarioNuevo;
	}

	public void setCalendarioVencimientosCalculado(
			List<Date> calendarioVencimientosCalculado) {
		this.calendarioVencimientosCalculado = calendarioVencimientosCalculado;
	}

	private void rellenarCalendarioDemoras(List<Date> calendario, Date fechaAux, Date fechaPost, Date fechaFin,
			Boolean finMes) {
		Date fechaIni = fechaAux;
		while (fechaAux.compareTo(fechaFin)<0) {
			calendario.add(fechaAux);
			fechaAux = getPlanInteresPorDefectoVigente().getPeriodicidadLiquidacion().aplicarPeriodicidad(fechaIni,
					fechaAux);
			if (finMes)
				fechaAux = getFechaParaFinMes(fechaAux);
			fechaPost = fechaAux;
		}
		if (fechaPost!=null)
			calendario.add(fechaPost);
	}

	/**
	 * Este método quita de la lista de tipos recibida como parámetro
	 * los tipos que no sean absoluto resto y porcentuales.
	 *
	 * @author salonso
	 *
	 * @param tiposOriginales
	 * @return
	 */
	public Set<TipoInteresFijado> obtenerTiposParaResto(Set<TipoInteresFijado> tiposOriginales) {
		Set<TipoInteresFijado> tiposTratados = new HashSet<TipoInteresFijado>();
		for (TipoInteresFijado tipo : tiposOriginales) {
			if (!tipo.esAbsoluto()||tipo.getTipoInteres()==EnumTiposInteresesFijados.ABSOLUTO_RESTO)
				tiposTratados.add(tipo);
		}
		return tiposTratados;
	}

	/**
	 * Este método quita de la lista de tipos recibida como parámetro
	 * los tipos que no sean absoluto resto y porcentuales.
	 *
	 * @author salonso
	 *
	 * @param tiposOriginales
	 * @return
	 */
	public Set<TipoInteresFijado> obtenerTiposParaCapitalFrances(Set<TipoInteresFijado> tiposOriginales) {
		Set<TipoInteresFijado> tiposTratados = new HashSet<TipoInteresFijado>();
		for (TipoInteresFijado tipo : tiposOriginales) {
			if (!tipo.esAbsoluto()||tipo.getTipoInteres()==EnumTiposInteresesFijados.ABSOLUTO_CAPITAL)
				tiposTratados.add(tipo);
		}
		return tiposTratados;
	}

	public Set<TipoInteresFijado> obtenerTiposParaCapital(Set<TipoInteresFijado> tiposOriginales)
			throws PlanEventoException {
		// INI -ICO-35719 - 12-02-2015 -Se calcula la demora teniendo en cuenta el margen y el tipo de interés de demora
		if (getOperacion().getPlanAmortizacion().isPlanAmortizacionFrances() || (getOperacion().getTipoOperacionActivo().equals(TipoOperacionActivoEnum.EL)) || getOperacion().getCalculoEspecial() )
			return obtenerTiposParaCapitalFrances(tiposOriginales);
		else {
			Set<TipoInteresFijado> tiposMenores = getOperacion().getPlanInteres().getPlanInteresPorDefectoVigente().getTipoInteres();
			//Si no tiene fijado el tipo de operación, le fijo el de la primera disposición.
			if(tiposMenores.size() == 0 && getOperacion().getPrimeraDisposicion()!=null) {
				tiposMenores = getOperacion().getPrimeraDisposicion().getPlanInteresDisposicion().getPlanInteresPorDefectoVigente().getTipoInteres();
			}
			return obtenerRestaTipos(tiposOriginales,
					tiposMenores,
					ConstantesError.ERROR_TIPO_DEMORA_DEBE_SER_MAYOR_IGUAL_TIPO_INTERES);
		}
		// FIN -ICO-35719 - 12-02-2015
	}

	/**
	 * Agrupa los saldos vencidos no cobrados para el resto de conceptos
	 * Sin incluir demoras
	 */
	protected List<CantidadTramo> obtenerSaldosParaResto(SaldosTotalesOp saldos, Set<String> conceptosDemora, 
				List<Date> calendarioDemoras, List<Date> fechaAVAS) {

		List<CantidadTramo> saldosMora = new ArrayList<CantidadTramo>();
		HashMap<Date, BigDecimal> saldosFecha = new HashMap<Date, BigDecimal>();
		boolean parciales=false;
		Date fechaFin=calendarioDemoras.get((calendarioDemoras.size()-1));
		List<Date> fechaSaldos=new ArrayList<Date>(); 
		BigDecimal importeVcto=BigDecimal.ZERO;


		for(SaldosOp saldosOp : saldos.getSaldosOperacion()) {
			
			Date fechaInicioTramo = saldosOp.getFechaSaldo();
			fechaSaldos.add(fechaInicioTramo);
			BigDecimal importe = BigDecimal.ZERO;

			for (String concepto : conceptosDemora) {

				//obtienen los eventos del concepto correspondiente
				if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.LIQUIDACIONES_PARCIALES){
					importe = importe.add(saldosOp.getSaldoMoraInteres());
					parciales=true;
				}
				else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.INTERESES //ICO-100328
						&&(!conceptosDemora.contains(ConceptoDemoraEnum.LIQUIDACIONES_PARCIALES.getCodigo())||!(this.getOperacion() instanceof OperacionEL)))
					importe = importe.add(saldosOp.getSaldoMoraInteres());
				else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.COMIS_APER_EST_ASEG)
					importe = importe.add(saldosOp.getSaldoMoraComisionAperEstAseg());
				else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.GASTOS_SUPLIDOS)
					importe = importe.add(saldosOp.getSaldoMoraComisionGastosSuplidos());
				else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.COMISION_AGENCIA)
					importe = importe.add(saldosOp.getSaldoMoraComisionAgencia());
				else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.RESTO_COMSIONES)
					importe = importe.add(saldosOp.getSaldoMoraComisionResto());
				else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.CAPITAL)
					importe = importe.add(saldosOp.getSaldoMoraAmortizacion());
				else if (ConceptoDemoraEnum.getEnumByCode(concepto)==ConceptoDemoraEnum.CUOTA_CLIENTE)
					importe = importe.add(saldosOp.getSaldoMoraCuotaCliente());
				else	
					continue;
			}		 
			
			if (calendarioDemoras.contains(fechaInicioTramo)){ //si la fecha es un vcto recupero su saldo
				importeVcto=saldosOp.getSaldoInteresesV();
			}
			
			if( fechaAVAS.contains(fechaInicioTramo) && calendarioDemoras.contains(fechaInicioTramo)) { //si AVA no meter para calcular demora
				saldosFecha.put(fechaInicioTramo, importe);
			} else  if (fechaAVAS.contains(fechaInicioTramo) && !calendarioDemoras.contains(fechaInicioTramo)  && this.getOperacion() instanceof OperacionEL) { // no meter a no ser que tenga concepto de parciales
				if (parciales){
					saldosFecha.put(fechaInicioTramo, importe);
				}else {
					if(saldosOp.getSaldoTotalVC().compareTo(BigDecimal.ZERO)==1){ //si tvc es mayor que cero significa hay un cobro por lo que meto 
						if (importeVcto.subtract(saldosOp.getSaldoInteresesVC()).compareTo(BigDecimal.ZERO)<=0){
							saldosFecha.put(fechaInicioTramo, BigDecimal.ZERO);
						}else {
							saldosFecha.put(fechaInicioTramo,  importeVcto.subtract(saldosOp.getSaldoInteresesVC())); 
						}
					}else { 
						break;
					}
				}
			} else if (!calendarioDemoras.contains(fechaInicioTramo) && this.getOperacion() instanceof OperacionEL) { 
				//como no entro en los if anteriores significa que tiene que ser un cobro, no es ava, ni vcto, por lo que es cobro
				if (parciales){
					saldosFecha.put(fechaInicioTramo, importe);
				}else {
						if (importeVcto.subtract(saldosOp.getSaldoInteresesVC()).compareTo(BigDecimal.ZERO)<=0){
							saldosFecha.put(fechaInicioTramo, BigDecimal.ZERO); 
						}else {
							saldosFecha.put(fechaInicioTramo, importeVcto.subtract(saldosOp.getSaldoInteresesVC())); 
						}
				}						
			} else {
				saldosFecha.put(fechaInicioTramo, importe);
			}
		}

		//crea los tramos de saldo en funcion de los saldos obtenidos
		List<Date> fechasSaldo = new ArrayList<Date>(saldosFecha.keySet());
		Collections.sort(fechasSaldo);

		CantidadTramo tramo = null;
		for(Date fecha : fechasSaldo) {
			if(tramo != null) {
				tramo.setfechaFin(fecha);
			}
			tramo = new CantidadTramo(fecha, null, saldosFecha.get(fecha)) ; 
			if(tramo.getCantidad().compareTo(BigDecimal.ZERO)>0){
				saldosMora.add(tramo);
			}
		}
		
		if(tramo != null)
			tramo.setfechaFin(fechaFin);

		return saldosMora;  
	}

	/***
	 * Genera las demora padre en cada fecha del calendario recibido, y asocia los eventos hijo
	 * de las 2 listas recibidas.
	 * Este método asigna el concepto adecuado a cada evento y establece la relacion con los planes.
	 *
	 * @author jc,salonso
	 *
	 * @param calendario Lista de fechas del calendario
	 * @param parcialesCapital Liquidación de demoras de capital HIJA
	 * @param parcialesResto Liquidación de demoras de resto HIJA
	 * @return Lista con las demoras padre generadas
	 * @throws POJOValidationException
	 */
	public List<EventoAutomatico> agruparDemoras(List<Date> calendario, List<EventoAutomatico> parcialesCapital,
			List<EventoAutomatico> parcialesResto, List<Date> festivos)
			throws POJOValidationException {
		List<EventoAutomatico> demorasPadre = new ArrayList<EventoAutomatico>();
		
		System.out.println("=== INICIO agruparDemoras ===");
		System.out.println("ParcialesCapital.size: " + parcialesCapital.size());
		System.out.println("ParcialesResto.size: " + parcialesResto.size());

	


		if(!parcialesCapital.isEmpty() || !parcialesResto.isEmpty()
			&& calendario.size()>0) {
			Date fechaAnterior = calendario.get(0);
			/*
			 * La primera fecha del calendario recibido es utilizada como principio
			 * de tramo de la primera liquidación, por tanto en esa fecha, no debe
			 * generar demoras.
			 */
				calendario.remove(0);
			//Debemos generar un padre en cada fecha del calendario recibido.
			for (Date fecha : calendario) {
				fecha = FechaUtils.convertirFecha(fecha);
				BigDecimal importe = BigDecimal.ZERO;
				LiqDemoras demTotal = new LiqDemorasImp();

				//se rellenan los datos obligatorios de los eventos
				demTotal.setFechaEvento(fecha);
				demTotal.setFechaInicio(fechaAnterior);
				demTotal.setFechaVencimientoAjustada(fecha); // ICO-62994 Se necesita calcular la fecha de cobro de la demora
				demTotal.setEsEstadoActivo(true);
				demTotal.setConcepto(ConceptoLiquidacionDemoraEnum.DEMORA.getCodigo());
				demTotal.setEventoInfo(new EventoInfoImp());
				demTotal.getEventoInfo().setFechaInicioValidez(fechaAnterior);
				demTotal.getEventoInfo().setFechaFinValidez(fecha);

				Date fechaAnteriorParcial = fechaAnterior;
				//Recorremos cada evento capital generado y asociamos adecuadamente a su evento padre
				for (EventoAutomatico evCap : parcialesCapital) {
					//sólo de evento:
					//-Sea menor o igual a la fecha que estamos iterando
					//-Sea mayor a la fecha anterior (si ésta existe)
					if (!evCap.getFechaEvento().after(fecha)
							&&(evCap.getFechaEvento().after(fechaAnterior))) {
						//Hemos encontrado un hijo que debe asociarse al evento padre
						//-Aumentamos el importe del padre
						importe = importe.add(evCap.getImporte().getCantidad());
						//Asociamos el padre al hijo y viceversa
						demTotal.addEventoParcialToSet(evCap);
						evCap.setFechaVencimientoAjustada(evCap.getFechaEvento());
						//Mejora 59P se asigna la informacion del tipo interes del evento en funcion del periodo con el que se ha calculado
						if(evCap.getEventoInfo().getFechaInicioValidez().before(fechaAnteriorParcial))
							evCap.getEventoInfo().setFechaInicioValidez(fechaAnteriorParcial);
						evCap.getEventoInfo().setFechaFinValidez(evCap.getFechaEvento());
						//de paso le colocamos el concepto adecuado
						((LiqDemoras) evCap).setConcepto(ConceptoLiquidacionDemoraEnum.CAPITAL.getCodigo());
						//auditoria
						auditarEventoAutomatico(evCap);
						fechaAnteriorParcial = evCap.getFechaEvento();
					}
				}

				//Recorremos cada evento resto generado y asociamos adecuadamente a su evento padre
				for (EventoAutomatico evRes : parcialesResto) {
					//sólo se añaden los eventos de resto cuya fecha de evento:
					//-Sea menor o igual a la fecha que estamos iterando
					//-Sea mayor a la fecha anterior (si ésta existe)
					if (!evRes.getFechaEvento().after(fecha)
							&&(fechaAnterior==null||evRes.getFechaEvento().after(fechaAnterior))) {
						//Hemos encontrado un hijo que debe asociarse al evento padre
						//-Aumentamos el importe del padre
						importe = importe.add(evRes.getImporte().getCantidad());
						//Asociamos el padre al hijo y viceversa
						demTotal.addEventoParcialToSet(evRes);
						evRes.setFechaVencimientoAjustada(evRes.getFechaEvento());
						//Mejora 59P se asigna la informacion del tipo interes del evento en funcion del periodo con el que se ha calculado
						if(evRes.getEventoInfo().getFechaInicioValidez().before(fechaAnterior)) //ICO-63283 fechaAnteriorParcial si antes hay demora capital es incorrecta
							evRes.getEventoInfo().setFechaInicioValidez(fechaAnterior);
						evRes.getEventoInfo().setFechaFinValidez(evRes.getFechaEvento());
						//de paso le colocamos el concepto adecuado
						((LiqDemoras) evRes).setConcepto(ConceptoLiquidacionDemoraEnum.RESTO.getCodigo());
						//auditoria
						auditarEventoAutomatico(evRes);
						fechaAnteriorParcial = evRes.getFechaEvento();
					}
				}

				//Solo genera demora si se han generado demoras por concepto
				if(!demTotal.getEventosParciales().isEmpty()) {
					//se establece el importe de la demora
					demTotal.setImporte(new ImporteImp(importe));
					if(demTotal.getEventosParciales().size() == 1)
						demTotal.getEventoInfo().setPorcentajeAplicado(demTotal.getEventosParciales().iterator()
								.next().getEventoInfo().getPorcentajeAplicado());
					else
						demTotal.getEventoInfo().setPorcentajeAplicado(null);

					/*
					 * Asociamos la demora al plan.
					 */
					demTotal.setPlanEvento(this);
					demTotal.setOperacion(getOperacion());

					//auditoria
					auditarEventoAutomatico(demTotal);
					demorasPadre.add(demTotal);
					// Sout para ver los parciales asociados a este padre
					System.out.println("PADRE: " + demTotal.getFechaInicio() + " -> " + demTotal.getFechaEvento());
					if (demTotal.getEventosParciales() != null) {
					    for (EventoAutomatico parcial : demTotal.getEventosParciales()) {
					        System.out.println("  Parcial: fechaEvento=" + parcial.getFechaEvento() + " importe=" + parcial.getImporte().getCantidad());
					    }
					} else {
					    System.out.println("  (Sin parciales asociados)");
					}
				}
				//Guardamos la fecha anterior que nos servirá para la siguiente iteración
				fechaAnterior = fecha;
			}
			
			if(!(this.getOperacion() instanceof OperacionFD )){
				//Este método calcula las fechas de vencimiento ajustadas
				ajustarFechasVencimiento(demorasPadre, this.getOperacion().getPlanAjustableDias(), festivos);
			}
			
			ajustarFechasPago(demorasPadre, this.getOperacion().getPlanAjustableDias(), festivos); // ICO-62994 Se ajusta la fecha de cobro recientemente informada
		}
		
		System.out.println("=== FIN agruparDemoras ===");
		for (EventoAutomatico padre : demorasPadre) {
		    System.out.println("PADRE: " + padre.getFechaInicio() + " -> " + padre.getFechaEvento() +
		        " | Parciales: " + (padre.getEventosParciales() != null ? padre.getEventosParciales().size() : 0));
		}
		return demorasPadre;
	}

	/**
	 * Método que sobrescribe el de la clase padre. Evita volver a construir
	 * las fechas de vencimiento ajustada, si ya fueron ajustadas las
	 * fechas de vencimiento.
	 */
	@Override
	public void ajustarFechasVencimiento(EventoAutomatico eventoPadre, PlanAjustableDias planAjustableDias,
			List<Date> festivos) throws PlanEventoException {
		try {
			if (planAjustableDias.getAjustableDiasEfectivoPago() != null && planAjustableDias.getAjustableDiasEfectivoPago()) {//si ya fue ajustado evitamos hacer de nuevo el tratamiento
				eventoPadre.setFechaVencimientoAjustada(eventoPadre.getFechaEvento());
			} else {//depende de la convención de día hábil.
				//super.ajustarFechasVencimiento(eventoPadre, planAjustableDias, festivos);
				this.ajustarFechasVencimientoDemoras(eventoPadre, planAjustableDias, festivos);
			}
		} catch (Exception e) {
			LOG.error(PlanDemoraImp.class.getName()+" tratarPlanAjustableDias "+e.getMessage()+". "
					+e.getStackTrace()[0]);
			POJOValidationMessage m = new POJOValidationMessage("Error al ajustar"+" las fechas de las demoras");
			throw new PlanEventoException(m);
		}
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime*result+((conceptosDemora==null) ? 0 : conceptosDemora.hashCode());
		/*
		 * result = prime * result + ((operacionActivo == null) ? 0 :
		 * operacionActivo.hashCode()); result = prime result +
		 * ((planInteresPorDefectoVigente == null) ? 0 :
		 * planInteresPorDefectoVigente.hashCode());
		 */
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this==obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass()!=obj.getClass())
			return false;
		final PlanDemoraImp other = (PlanDemoraImp) obj;
		if (conceptosDemora==null) {
			if (other.conceptosDemora!=null)
				return false;
		} else if (!conceptosDemora.equals(other.conceptosDemora))
			return false;
		/*
		 * if (operacionActivo == null) { if (other.operacionActivo != null)
		 * return false; } else if
		 * (!operacionActivo.equals(other.operacionActivo)) return false; if
		 * (planInteresPorDefectoVigente == null) { if
		 * (other.planInteresPorDefectoVigente != null) return false; } else if
		 * (!planInteresPorDefectoVigente
		 * .equals(other.planInteresPorDefectoVigente)) return false;
		 */
		return true;
	}

	public Pojo copyBean() {

		PlanDemora pd = new PlanDemoraImp();
		copiarDatos(pd);
		return pd;
	}

	private void copiarDatos(PlanDemora planDemora) {

		/*
		 * Copiado de propiedades propias del objeto
		 */
		// conceptos de demora
		Iterator<String> it = getConceptosDemora().iterator();
		while (it.hasNext()) {
			planDemora.getConceptosDemora().add(it.next());
		}

		// planInteresPorEvento
		planDemora.setPlanInteresPorDefectoVigente((PlanInteresPorEvento) getPlanInteresPorDefectoVigente().copyBean());
		
		if (getPlanReferenciaRenovable()!=null) {
			planDemora.setPlanReferenciaRenovable((PlanReferenciaRenovable) getPlanReferenciaRenovable().copyBean());
		}
		
		/*
		 * Llamada al padre
		 */
		super.copyBean(planDemora);

	}

	@Override
	public void desactivar() {
		// desactivamos este plan
		super.desactivar();
	}

	@Override
	public OrdenEjecucionPlanEventoEnum getOrdenEjecucion() {
		return OrdenEjecucionPlanEventoEnum.PLAN_DEMORA;
	}

	@Override
	public boolean isConPlanInteresPorEvento() {
		return true;
	}

	@Override
	public boolean necesitaSaldosActualizados() {
		return true;
	}

	private Set<TipoInteresFijado> homologarBaseCalculoTiposDemora(Set<TipoInteresFijado> tiposInteresDemora,
			BaseCalculoEnum baseCalculoDemora, BaseCalculoEnum baseCalculoInteres){
		// Mejora 114P
		// BASES DE CALCULO A HOMOLOGAR:
		//	-base calculo Operacion
		//	-base calculo Demora
		//	-base calculo referencia tipo Demora

		BigDecimal valorBCOperacion = baseCalculoInteres.valorHomologacion(baseCalculoInteres);
		BigDecimal valorBCDemora = baseCalculoDemora.valorHomologacion(baseCalculoDemora);
		BigDecimal valorBCTipoDemora = new BigDecimal(1);

		Set<TipoInteresFijado> tiposInteresCalculadosDemora = new HashSet<TipoInteresFijado>();

		//Se recorren los tipos de interes fijados de Demora
		if(!baseCalculoDemora.equals(baseCalculoInteres))
		{
			for(TipoInteresFijado tipoFijado : tiposInteresDemora)
			{
				TipoInteresFijado tipoCalculado = (TipoInteresFijado) tipoFijado.copyBean();

				// Obtencion base calculo referencia tipo Demora si es variable
				if(tipoFijado.getPlanInteresPorEvento()!= null && tipoFijado.getPlanInteresPorEvento().isVariable()){
					ReferenciaTipoInteresPA refTipoDemora = ((PlanInteresPorEventoRenovable)tipoFijado.getPlanInteresPorEvento())
																					.getPlanReferenciaVariable().getReferenciaTipoInteres();
					BaseCalculoEnum baseBCTipoDemora = BaseCalculoEnum.getEnumByCode(refTipoDemora.getBaseCalculo());
					if(baseBCTipoDemora != BaseCalculoEnum.VALOR_POR_DEFECTO){
						valorBCTipoDemora = baseBCTipoDemora.valorHomologacion(baseBCTipoDemora);
					}
				}
				// Homologacion de la base de calculo para cada tipo
				BigDecimal valorFijado = tipoFijado.getValor_fijado();
				// Base de referencia de Demora con base de Demora
				valorFijado = new BigDecimal(valorFijado.multiply(valorBCDemora).doubleValue()/valorBCTipoDemora.doubleValue());
				// Resultante con base de operacion
				valorFijado = new BigDecimal(valorFijado.multiply(valorBCOperacion).doubleValue()/valorBCDemora.doubleValue());

				tipoCalculado.setValor_fijado(valorFijado.setScale(5,BigDecimal.ROUND_HALF_UP));
				valorBCTipoDemora = new BigDecimal(1);
				tiposInteresCalculadosDemora.add(tipoCalculado);
			}
		}
		else
		{
			tiposInteresCalculadosDemora=tiposInteresDemora;
		}

		return tiposInteresCalculadosDemora;
	}

	public PlanReferenciaRenovable getPlanReferenciaRenovable() {
		return planReferenciaRenovable;
	}

	public void setPlanReferenciaRenovable(PlanReferenciaRenovable planReferenciaRenovable) {
		if (planReferenciaRenovable!=null)
			planReferenciaRenovable.setPlanEvento(this);
		this.planReferenciaRenovable = planReferenciaRenovable;

	}

	public Boolean isVariable() {
		return this.planReferenciaRenovable!=null;
	}
	
    public List<Date> getCalendarioVencimientosNew(Date fechaLimite){
    	List<Date> fechas = new ArrayList<Date>();

    	// fecha prevista primera amortizacion
    	Date fechaFinOperacion=this.getOperacion().getFechaFinOperacion();
    	//ICO-73341 tener en cuenta la fecha primera amortizacion si coincidencia vencimientos en FAD
    	Date fechaPrimeraAmort=FechaUtils.sumarAnyos(this.getOperacion().getPlanAmortizacion().getFechaAmortizacionPrevista(),
    			this.getOperacion().getPlanAmortizacion().getAnyosCarencia());

    	//ICO-63282 coger la fecha renovacion demoras si existe
    	Date fechaPrimeraLiquidacionPrevista;
    	if(getPlanReferenciaRenovable()!= null && getPlanReferenciaRenovable().getFechaPrimeraRenovacion()!= null){
    		fechaPrimeraLiquidacionPrevista= getPlanReferenciaRenovable().getFechaPrimeraRenovacion();
    	}else {
    		fechaPrimeraLiquidacionPrevista = this.getOperacion().getPlanInteres().getPlanInteresPorDefectoVigente().getFechaPrimerEvento();
    	}
    	String periodicidad=getPlanInteresPorDefectoVigente().getPeriodicidadLiquidacion().getCodigo();
    	Date fechaAux = fechaPrimeraLiquidacionPrevista;
    	//ICO-73341 demoras después fin operación para FAD
    	if(fechaLimite!=null && this.getOperacion() instanceof OperacionFF
    			&& fechaLimite.after(fechaFinOperacion)) { //demoras después fin operación
    			fechaFinOperacion=fechaLimite;
    	}
    	Boolean cambiaFecha= Boolean.FALSE;
    	int i=0;
    	
    	while (fechaAux.before(fechaFinOperacion)){ 
    		
    		if(this.getOperacion() instanceof OperacionFF
    			&&	getOperacion().getCoincidenciaVencimientos()
    				&& fechaPrimeraAmort!=null 
    				&& (fechaAux.after(fechaPrimeraAmort)||fechaAux.equals(fechaPrimeraAmort))
    				&&!cambiaFecha) {
    			fechaAux= fechaPrimeraAmort;
    			fechaPrimeraLiquidacionPrevista=fechaPrimeraAmort;
    			i=0;
    			cambiaFecha = Boolean.TRUE;
    		}
    		// fin de mes
    		PlanAjustableDias planAjustableDias = getOperacion().getPlanAjustableDias();
     		if(planAjustableDias.getPagableFinMes() && !cambiaFecha){ //ICO-84227 no llevar a fin de mes si ya hay coincidencia vencimientos
     			fechaAux = FechaUtils.llevarFinMes(fechaAux);
     		}

    		fechas.add(fechaAux);
    		if(periodicidad.equals(PeriodicidadEnum.DIARIA.getCodigo())) {
    			fechaAux=PrestamosUtils.agregarDias(fechaPrimeraLiquidacionPrevista, i+1);
    		}
    		else if(periodicidad.equals(PeriodicidadEnum.SEMANAL.getCodigo())) {
    			fechaAux=PrestamosUtils.agregarDias(fechaPrimeraLiquidacionPrevista, (i+1)*7);
    		}
    		else if(periodicidad.equals(PeriodicidadEnum.QUINCENAL.getCodigo())) {
    			fechaAux=PrestamosUtils.agregarDias(fechaPrimeraLiquidacionPrevista, (i+1)*15);
    		}
    		else if(periodicidad.equals(PeriodicidadEnum.MENSUAL.getCodigo())) {
    			fechaAux=PrestamosUtils.agregarMeses(fechaPrimeraLiquidacionPrevista, i+1);
    		}
    		else if(periodicidad.equals(PeriodicidadEnum.TRIMESTRAL.getCodigo())) {
    			fechaAux=PrestamosUtils.agregarMeses(fechaPrimeraLiquidacionPrevista, (i+1)*3);
    		}
    		else if(periodicidad.equals(PeriodicidadEnum.SEMESTRAL.getCodigo())) {
    			fechaAux=PrestamosUtils.agregarMeses(fechaPrimeraLiquidacionPrevista, (i+1)*6);
    		}
    		else if(periodicidad.equals(PeriodicidadEnum.ANUAL.getCodigo())) {
    			fechaAux=PrestamosUtils.agregarMeses(fechaPrimeraLiquidacionPrevista, (i+1)*12);
    		}
    		else if(periodicidad.equals(PeriodicidadEnum.BIANUAL.getCodigo())) {
    			fechaAux=PrestamosUtils.agregarMeses(fechaPrimeraLiquidacionPrevista, (i+1)*24);
    		}
    		i++;
    	}

    	Collections.sort(fechas);
    	return fechas;
    }
    
    /**
     * Aplicacion de CODIGO DIA HABIL y AJUSTABLE DIAS EFECTIVO PAGO, si procede:
     * - CODIGO DIA HABIL. Esta variable afecta a la FECHA DE VENCIMIENTO AJUSTADA.
     *
     * Por defecto cuando un evento ha calculado su fecha de evento, tiene que informársele
     * la fecha de si genera demoras.
     * La fecha de vencimiento ajustada es calculada en función de la convención de día
     * hábil.
     */
    public void ajustarFechasVencimientoDemoras(EventoAutomatico eventoPadre, PlanAjustableDias planAjustableDias, List<Date> festivos) throws PlanEventoException{

    	try{
    		Date fechaSiGeneraDemoras = null;
    		
    		fechaSiGeneraDemoras = getFechaParaAjuste(planAjustableDias, eventoPadre.getFechaEvento(), festivos);
    		
    		eventoPadre.setFechaEvento(fechaSiGeneraDemoras);

    	} catch (Exception e) {
    		LOG.error( PlanEventoImp.class.getName() + " tratarPlanAjustableDias " + e.getMessage() + ". " + e.getStackTrace()[0]);
    		POJOValidationMessage m = new POJOValidationMessage("Error al ajustar" + " las fechas de los eventos");
	        throw new PlanEventoException(m);
		}

	}
    
    /**
     * Calcula la fecha para ajustarla segun el codigo de dia habil
     * @throws PlanEventoException
     */
	private Date getFechaParaAjuste(PlanAjustableDias planAjustableDias, Date date, List<Date> festivos) throws PlanEventoException {
		Date fDate = date;
		//boolean isOperacionFD = planAjustableDias.getOperacion().getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FD.getCodigo());
		try {

		    boolean isFinMesGeneral = planAjustableDias.getPagableFinMes()!=null?planAjustableDias.getPagableFinMes().booleanValue():false;
		    boolean isFinMesDemoras = planAjustableDias.getFinMesDemoras()!=null?planAjustableDias.getFinMesDemoras().booleanValue():false;

		    //ICO-84227 comprobamos si hay coincidencia vencimientos (solo FAD)
		    Date fechaPrimeraAmort=null;
		    if (planAjustableDias.getOperacion().getPlanAmortizacion()!=null) {
		    	fechaPrimeraAmort=FechaUtils.sumarAnyos(planAjustableDias.getOperacion().getPlanAmortizacion().getFechaAmortizacionPrevista(),
		    			planAjustableDias.getOperacion().getPlanAmortizacion().getAnyosCarencia());
		    }
		    boolean isCoincideVenc = Boolean.TRUE.equals(planAjustableDias.getOperacion().getCoincidenciaVencimientos())
		    							&& fechaPrimeraAmort!=null && !date.before(fechaPrimeraAmort);
			if((isFinMesGeneral && !isCoincideVenc) || isFinMesDemoras) { //ICO-69204 - Prevalece fecha fin de caratula antes que check de fin de mes
			    fDate = FechaUtils.llevarFinMes(date);
			}
			
			fDate = UtilsCalculoFechaHabil.calcularFechaEfectiva(fDate, planAjustableDias.getConvencionDiaHabil(), festivos);

		} catch (Exception e) {
			throw new PlanEventoException(e);
		}

		return fDate;
	}

}