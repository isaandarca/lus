 package es.ico.prestamos.ejb.cuadroevento;

 /*
 20140404 - LM - ICO-11476 CONTABILIDAD - Envio periodificaciones con renovación de tipo fuera de fecha

 Cuando hay cambios que notificar al motor Contable, se actualiza la fecha de regeneración a la fecha
 del primer cambio que deba notificarse, con las siguientes excepciones:
 - si la fecha registrada es anterior: no se actualiza
 - si la fecha registrada es igual: se marca reenviar a SI para que no dé por enviados todos los datos hasta esa fecha
  
  20140415 - LM - ICO-11675 CONTABILIDAD - Envio periodificaciones con renovación de tipo fuera de fecha
 Se modifica la gestión de envío de datos contables de modo que se reenvíen los datos de periodificaciones  
 desde la fecha de último envío de contabilidad cuando se actualice una operación a futuro.
 Para ello se gestiona un campo adiciona de la tabla pa_interfaz_contable_fecha llamado ReenviarPer que
 toma valores SI/NO.

20140626 - LM 
 Se detecta que no se actualizan correctamente las fechas contables con un cambio que coincide en fecha
con la fecha de regeneración y el día de hoy. Se corrige

20141016 - LM - ICO-33776 - CONTABILIDAD - Añadir campos de auditoria a la tabla de fechas de contabilidad
 Se implementan modificaciones para tratar los nuevos campos de la tabla 
 */
 


/**
 * Clase que accede a BBDD para consultar, insertar o modificar los saldos
 * **/
@Stateless(name = "RegeneraCuadroEventosHelper", mappedName = "RegeneraCuadroEventosHelper")
@Local(RegeneraCuadroEventosHelper.class)
@TransactionManagement(TransactionManagementType.CONTAINER)
public class RegeneraCuadroEventosHelperImp extends PrestamosBaseDAO implements RegeneraCuadroEventosHelper {

	/** Nombre de la clase. */
    private static String className = RegeneraCuadroEventosHelperImp.class.getName();

	private static String queryInsertEvento;
	private static String queryDeleteEventos;
	private static String queryDeleteEventosCobro;
	private static String queryDeleteEventosComisionesPrepagablesCobro;
	
	@EJB
	SaldosServiceLocal saldosService;


	@EJB
	CobrosJDBC cobrosJDBC;

	@EJB
	CobrosService cobrosService;


	@EJB
	protected DisposicionJDBCLocal disposicionJDBC;

	@EJB
	private InterfazNucleoDAOLocal interfazNucleoDAO;
	
	@EJB
	protected OperacionActivoDAOLocal operacionActivoDAO;
	
	@EJB
    private ModuloPagosPSDAO moduloPagosDAO; //ICO-65728

	/**
	 * Devuelve todos los saldos de la operación correspondientes a una fecha dada almacenados en BBDD
	 *
	 * @param operacion
	 * @param fechaInicioPrimerEvento
	 * */
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public SaldosTotalesOp consultaSaldos(Operacion operacion, List<Long> idsDisposicion,
			Date fechaInicioPrimerEvento, Date fechaEjecucion, boolean cargarSaldosPosteriores) throws JDBCException {

		Long idOperacion = operacion.getId();
		Date fechaConsulta = null;
		if(!cargarSaldosPosteriores) {
			fechaConsulta = FechaUtils.restaUnDiaCalendario(fechaEjecucion);
			if(fechaInicioPrimerEvento != null && !fechaInicioPrimerEvento.before(fechaConsulta))
				fechaInicioPrimerEvento = null;
		}
		//Se consultan los saldos a un dia anterior, una caso simulacion no se han borrado los saldos a la fecha actual
		//por lo que estos se recuperarian
		SaldosTotalesOp saldosOperacion = saldosService.consultaSaldos(idOperacion, idsDisposicion, fechaInicioPrimerEvento, fechaConsulta);
		// Se ordena los saldos de disposición por idsDiposiciones.
		ordenarSaldosDispo(saldosOperacion, operacion.getTipoOperacionActivo().getCodigo(), idsDisposicion);//INI - FIN ICO-63209
		
		//Saldo capital pendiente para subsidio
		Date fechaGeneracionSubsidio = null;
		for(PlanEvento planEvento : operacion.getPlanesEventos()){
    		if(planEvento instanceof PlanSubsidioOperacionDiferencialCuota){
    			if(fechaGeneracionSubsidio == null) {
    				fechaGeneracionSubsidio = getFechaGeneracionSubsidio(idOperacion, fechaEjecucion);
    				if(fechaGeneracionSubsidio != null) {
    					SaldosOp saldoCPVSubsidio = saldosService.getSaldosOp(idOperacion, fechaGeneracionSubsidio);
    					if(saldoCPVSubsidio != null)
    						saldosOperacion.addSaldosOperacion(saldoCPVSubsidio);
    				}
    			}
    			((PlanSubsidioOperacionDiferencialCuota) planEvento).setFechaRegeneracionCuotas(fechaGeneracionSubsidio);
    		 }
		}

		return saldosOperacion;
	}

	//INI ICO-63209
	private void ordenarSaldosDispo(SaldosTotalesOp saldosOperacion, String tipoOpe, List<Long> idsDisposicion) {
		Map<Long, SortedSet<SaldosDisp>> saldoDispo = saldosOperacion.getSaldosDisposiciones();
		if (!saldoDispo.isEmpty() && (tipoOpe.equals("FD") || tipoOpe.equals("VPO"))) {
			List<Entry<Long, SortedSet<SaldosDisp>>> listaDispodesord = saldoDispo.entrySet().stream()
					.collect(Collectors.toList());
			Map<Long, SortedSet<SaldosDisp>> saldoDispoOrderMap = new LinkedHashMap<Long, SortedSet<SaldosDisp>>();
			for (int i = 0; i < idsDisposicion.size(); i++) {
				for (int j = 0; j < listaDispodesord.size(); j++) {
					if (String.valueOf(listaDispodesord.get(j).getKey())
							.equals(String.valueOf(idsDisposicion.get(i)))) {
						saldoDispoOrderMap.put(idsDisposicion.get(i), listaDispodesord.get(j).getValue());
					}
				}
			}
			HashMap<Long, SortedSet<SaldosDisp>> saldoDipoOrdenada = new LinkedHashMap<Long, SortedSet<SaldosDisp>>(
					saldoDispoOrderMap);
			saldosOperacion.setSaldosDisposiciones(saldoDipoOrdenada);
		}
	}
	//FIN ICO-63209

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<EventoAutomatico> demorasAnteriores(Operacion operacion,
			 Date fechaEjecucion) throws JDBCException {

		List<EventoAutomatico> resultado =new ArrayList<EventoAutomatico>();

		//Pasar a StringBuilder o Buffer ya que esto es muy ineficiente
		String consultaSQL = " select e from "+LiqDemorasImp.class.getName() +
		" e LEFT JOIN FETCH e.eventosParciales "+
		" WHERE e.operacion.id = ? and trunc(e.fechaEvento) <= ? "+
		" and e.esEstadoActivo = ?"+
		" and e.eventoTotal is null "+
		" and e.class = ? " +
		" order by e.fechaEvento ";

		Query query = getEm().createQuery(consultaSQL);
		query.setParameter(1, operacion.getId());
		query.setParameter(2, fechaEjecucion);
		query.setParameter(3, true);
		query.setParameter(4, SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());

		resultado.addAll(query.getResultList());

		return resultado;
	}



	/**
	 * Obtiene las ids de disposiciones que deban regenerar saldos
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Long> getIdsDisposicion(Long idOperacion, Date fecha, List<Long> idsDisposicion) throws JDBCException {
		List<BigDecimal> idsDisposicionBigDeciml = new ArrayList<BigDecimal>();

		Query query = getEm().createNativeQuery("SELECT ID FROM PA_EVENTO" +
				" WHERE ID_OPERACION = ? AND TRUNC(FECHA_EVENTO) <= ? AND ES_ACTIVO = 1" +
				"AND ( DISCRIMINADOR =? OR DISCRIMINADOR =? OR DISCRIMINADOR =? OR DISCRIMINADOR =?)" +
				"ORDER BY FECHA_EVENTO, fechaaltaregistro");//INI -FIN ICO-63209 se añade el order by para que nos traiga en orden las dispos
		query.setParameter(1, idOperacion);
		query.setParameter(2, fecha);
		query.setParameter(3, SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(4, SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
		query.setParameter(5, SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
		query.setParameter(6, SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());
		idsDisposicionBigDeciml.addAll(query.getResultList());

		//Transformamos la lista a Long
		for (BigDecimal decimal : idsDisposicionBigDeciml) {
			if(decimal != null)
				idsDisposicion.add(decimal.longValue());
		}

		return idsDisposicion;
	}

	/**
	 * Obtiene las ids de disposiciones que deban regenerar su liquidacion
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	private List<Long> getIdsDisposicionConEventos(Long idOperacion, Date fecha, String tipoOpe) throws JDBCException {
	    List<Long> idsDisposicion = new ArrayList<Long>();

		List<BigDecimal> idsDisposicionBigDeciml = new ArrayList<BigDecimal>();

		Query query = getEm().createNativeQuery("SELECT DISTINCT PE.ID_DISPOSICION\n" +
				"FROM PA_PLANEVENTO pe\n" +
				" INNER JOIN PA_EVENTO D ON PE.ID_DISPOSICION=D.ID \n" +
				"	INNER JOIN PA_PLAN_CALCULO_INTERESES pci\n" +
				"		ON pci.ID_PLAN_EVENTO = pe.id AND pci.FECHAPRIMERALIQ >= ?  or basecalculo = 'ACT/ACT'\n" +
				" WHERE D.ES_ACTIVO=1 AND PE.ID_OPERACION = ?\n" +
				" AND D.FECHA_EVENTO <= ? \n" +
				"UNION\n" +
				"SELECT DISTINCT e.ID_EVENTO_ASOCIADO FROM PA_EVENTO e\n" +
				" INNER JOIN PA_EVENTO D ON D.ID=e.ID_EVENTO_ASOCIADO \n" +
				"WHERE D.ES_ACTIVO=1 AND E.ES_ACTIVO=1 AND e.DISCRIMINADOR = ? AND e.ID_OPERACION = ? AND e.FECHA_EVENTO >= ? AND D.FECHA_EVENTO <= ?\n");
		query.setParameter(1, fecha);
		query.setParameter(2, idOperacion);
		query.setParameter(3, fecha);
		query.setParameter(4, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION.getDiscriminador());
		query.setParameter(5, idOperacion);
		query.setParameter(6, fecha);
		query.setParameter(7, fecha);
		idsDisposicionBigDeciml.addAll(query.getResultList());

		//Transformamos la lista a Long
		for (BigDecimal decimal : idsDisposicionBigDeciml) {
			if(decimal != null)
				idsDisposicion.add(decimal.longValue());
		}

		return idsDisposicion;
	}

	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Operacion loadOperacion(Long idOperacion) throws JDBCException {
		Query query = getEm().createQuery("SELECT op FROM OperacionImp op" +
				" WHERE op.id = ?");
		query.setParameter(1, idOperacion);
		Operacion operacion = (Operacion) query.getSingleResult();

		completaOperacion(operacion);

	    try {
			operacion.setCodigoHost(getCodigoHostByOperacion(operacion.getId()));
		} catch (Exception e) {
			operacion.setCodigoHost(null);
		}

		return operacion;
	}

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Evento loadEvento (Long idEvento, EventosOperacion eventosOperacion, Operacion operacion, Date fechaEjecucion) throws JDBCException {
				
		Query query = getEm().createQuery("SELECT e FROM EventoImp e WHERE e.id = ?");
		query.setParameter(1, idEvento);
		Evento evento = (Evento) query.getSingleResult();
		
		evento = castTipoEvento(evento);

		if(evento instanceof LiquidacionComisionesImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.comisiones.LiquidacionComisionesImp e" +
					" LEFT JOIN FETCH e.eventoAsociado " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}
		if(evento instanceof SubsidioEventoImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.operacion.subsidios.SubsidioEventoImp e" +
					" LEFT JOIN FETCH e.eventoAsociado " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}

		if(evento instanceof LiquidacionInteresesAutomaticaDisposicionImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.liquidacionIntereses.LiquidacionInteresesAutomaticaDisposicionImp e" +
					" LEFT JOIN FETCH e.disposicion " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}

		if(evento.getTipoEvento().equals(TipoEventoEnum.DISPOSICION)) {
			query = getEm().createQuery("SELECT disp FROM DisposicionOperacionImp disp" +
					" JOIN FETCH disp.planesEventosAsociados pe " +
					" LEFT JOIN FETCH pe.planInteresPorDefectoVigente pi" +
					" WHERE disp.id = ?");
			query.setParameter(1, idEvento);
			DisposicionOperacion disposicion = (DisposicionOperacion) query.getSingleResult();
			disposicion.setOperacion(operacion);
			disposicion.setPlanesEventosAsociados(new HashSet<PlanEvento> ());

			query = getEm().createQuery("SELECT pe FROM PlanEventoImp pe" +
						" LEFT JOIN FETCH pe.planInteresPorDefectoVigente.tipoInteres " +
						" LEFT JOIN FETCH pe.disposicion " +
						" WHERE pe.disposicion.id = ?");
			query.setParameter(1, disposicion.getId());
			disposicion.addPlanesEventoToPlanesEventosAsociados(new HashSet<PlanEvento> (query.getResultList()));

			PlanInteresPorEvento planInteresPorEvento = disposicion.getPlanInteresDisposicion().getPlanInteresPorDefectoVigente();
			query = getEm().createQuery("SELECT ti FROM TipoInteresFijadoImp ti"+
					" WHERE ti.planInteresPorEvento.id = ?");
			query.setParameter(1, planInteresPorEvento.getId());
			planInteresPorEvento.setTipoInteres(new HashSet<TipoInteresFijado>());
			planInteresPorEvento.addTiposInteres(new HashSet<TipoInteresFijado>(query.getResultList()));

			//Liquidacion intereses manual disposicion
			query = getEm().createQuery("SELECT em FROM LiquidacionInteresesManualDisposicionImp em"+
						" WHERE em.disposicion.id = ? " +
						" and em.fechaEvento < ? and em.esEstadoActivo=?");
			query.setParameter(1, idEvento);
			query.setParameter(2, fechaEjecucion);
			query.setParameter(3, true);
			disposicion.setEventosManuales(new HashSet<EventoManual>());
			disposicion.addEventosManualesToSet(new HashSet<EventoManual>(query.getResultList()));

			if(eventosOperacion!=null)
				eventosOperacion.addEventosManuales(disposicion.getEventosManuales());

			evento = disposicion;

		} else if(evento instanceof AmortizacionManual) {
			query = getEm().createQuery("SELECT amr FROM AmortizacionManualImp amr" +
					" LEFT JOIN FETCH amr.planesEventosAsociados pe " +
					" WHERE amr.id = ? and amr.esEstadoActivo=?");
			query.setParameter(1, idEvento);
			query.setParameter(2, true);
			evento = (Evento) query.getSingleResult();
		}
	
		Set<EventoAutomatico> eventosDependientes = new HashSet<EventoAutomatico>();
		List<ConEventoAsociado> eventosDependientesTemp = new ArrayList<ConEventoAsociado>();
		
		// ICO-49006 - 26/09/2017
		// Se cargan los eventos asociados a idEvento directamente desde la entidad EventoImp
		// Anteriormente se cargaban los eventos por su tipo, por lo que se hacían numerosas
		// consultas (1 por cada entidad / tipo de evento)
		query = getEm().createQuery("SELECT e FROM EventoImp e" +
				" WHERE e.eventoAsociado.id = ?  and e.esEstadoActivo=?");
		query.setParameter(1, idEvento);
		query.setParameter(2, true);
		eventosDependientesTemp.addAll(query.getResultList());
		
		for(ConEventoAsociado eventoTemp : eventosDependientesTemp) {
			ConEventoAsociado eventoDependiente = (ConEventoAsociado)loadEvento(eventoTemp.getId(), eventosOperacion, operacion,fechaEjecucion);
			eventoDependiente.setEventoAsociado(evento);
			if(!eventosDependientes.contains(eventoDependiente)) {
				eventosDependientes.add(eventoDependiente);
			}
		}
		evento.getEventosDependientes().clear();
		evento.getEventosDependientes().addAll(eventosDependientes);
		
		evento.getCobros().clear();

		//Incidencia 12137, dejaba mal los saldos por que al momento de recuperar
		//las amortizaciones los recuperaba como cobrado lo cual no deberia ser por
		//que se borran todos los saldos despues de la fechaEjecucion
		query = getEm().createQuery("SELECT ce FROM CobroEventoImp ce "
				+ "JOIN FETCH ce.eventoAsociado ev "							// ICO-118218
				+ "JOIN FETCH ce.cobroPuntual cp "								// ICO-118218
				+ "WHERE ev.id = ? "
				+ "AND ( cp.fechaCobro < ? "
				+ "      OR (ev.fechaVencimientoAjustada IS NOT NULL "			// ICO-118218
				+ "          AND cp.fechaCobro <= ev.fechaVencimientoAjustada "	// ICO-118218
				+ "          AND ev.fechaEvento < ? "							// ICO-118218
				+ "      ) "													// ICO-118218
				+ ")");															// ICO-118218
		query.setParameter(1, idEvento);
		query.setParameter(2, fechaEjecucion);
		query.setParameter(3, fechaEjecucion); // ICO-118218
		evento.getCobros().addAll(query.getResultList());

		if(evento.getCobros().isEmpty()) {
			evento.getImporteCobrado().setCantidad(BigDecimal.ZERO);
		}
			

		if(evento.isManual() && eventosOperacion!=null) {
			eventosOperacion.addEventoManual((EventoManual) evento);
			//INI ICO-68057
			if (evento instanceof AmortizacionAnticipadaDevolucionFacturaImp) {
				eventosOperacion.addEventoDevolucionFactura((Evento) evento);
			}
			//FIN ICO-68057
		} else if(eventosOperacion!=null) {
			EventoAutomatico eventoAuto = (EventoAutomatico) evento;
			eventoAuto.getEventosParciales().clear();
			eventoAuto.getEventosParciales().addAll(new HashSet<EventoAutomatico>());
			//eventoAuto.setEventosParciales(new HashSet<EventoAutomatico>());
			if(eventoAuto.getPlanEvento() != null) {
				eventosOperacion.addEventoPlan(eventoAuto.getPlanEvento(), eventoAuto);
			}
			//ICO-67866 Se comenta el try-catch porque estaba poniendo a 0 el importeCobrado de evento
			/*try {
				eventoAuto.setImporteCobrado(new ImporteImp(BigDecimal.ZERO));
			} catch (POJOValidationException e) {
				LOG.error(className + "loadEvento(..):" + e.getMessage(), e);
			}*/
		}

		evento.setOperacion(operacion);

		return evento;
	}
	
	private Evento castTipoEvento (Evento evento) {
		
		Evento eventoCast = evento;
		if(evento.getTipoEvento().equals(TipoEventoEnum.LIQUIDACION_INTERESES)
				&& !evento.isManual()
				&& !(evento instanceof LiquidacionInteresesAutomaticaDisposicionImp)
				&& !(evento instanceof LiquidacionInteresesAutomaticaImp)
				&& !(evento instanceof LiquidacionInteresesAutomaticaOperacionImp)) {
			
			LiquidacionInteresesAutomaticaOperacion eventoLiquidacion = new LiquidacionInteresesAutomaticaOperacionImp();
			for(Field field: getAllFields(eventoLiquidacion.getClass())) {
				field.setAccessible(true);
				String fieldName = field.getName();
				try {
					PropertyUtils.setProperty(eventoLiquidacion, fieldName, PropertyUtils.getProperty(evento, fieldName));
				} catch (ReflectiveOperationException e) {
					LOG.debug("El campo {} no existe en el objeto evento", fieldName);
				}
			}
			
			eventoCast = eventoLiquidacion;
		}
		
		return eventoCast;
	}
	
	private static List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields;
    }

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Evento loadEventoEntidadesLocales (Long idEvento, EventosOperacion eventosOperacion, Operacion operacion, Date fechaEjecucion) throws JDBCException {

		Query query = getEm().createQuery("SELECT e FROM EventoImp e" +
				" WHERE e.id = ?");
		query.setParameter(1, idEvento);
		Evento evento = (Evento) query.getSingleResult();

		if(evento instanceof LiquidacionComisionesImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.comisiones.LiquidacionComisionesImp e" +
					" LEFT JOIN FETCH e.eventoAsociado " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}
		if(evento instanceof SubsidioEventoImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.operacion.subsidios.SubsidioEventoImp e" +
					" LEFT JOIN FETCH e.eventoAsociado " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}

		if(evento instanceof LiquidacionInteresesAutomaticaDisposicionImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.liquidacionIntereses.LiquidacionInteresesAutomaticaDisposicionImp e" +
					" LEFT JOIN FETCH e.disposicion " +
					" LEFT JOIN FETCH e.eventosParciales " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}

		if(evento instanceof LiquidacionInteresesAutomaticaOperacionImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.liquidacionIntereses.LiquidacionInteresesAutomaticaOperacionImp e" +
					" LEFT JOIN FETCH e.eventosParciales " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}

		if(evento.getTipoEvento().equals(TipoEventoEnum.DISPOSICION)) {
			query = getEm().createQuery("SELECT disp FROM DisposicionOperacionImp disp" +
					" JOIN FETCH disp.planesEventosAsociados pe " +
					" LEFT JOIN FETCH pe.planInteresPorDefectoVigente pi" +
					" WHERE disp.id = ?");
			query.setParameter(1, idEvento);
			DisposicionOperacion disposicion = (DisposicionOperacion) query.getSingleResult();
			disposicion.setOperacion(operacion);
			disposicion.setPlanesEventosAsociados(new HashSet<PlanEvento> ());

			query = getEm().createQuery("SELECT pe FROM PlanEventoImp pe" +
						" LEFT JOIN FETCH pe.planInteresPorDefectoVigente.tipoInteres " +
						" LEFT JOIN FETCH pe.disposicion " +
						" WHERE pe.disposicion.id = ?");
			query.setParameter(1, disposicion.getId());
			disposicion.addPlanesEventoToPlanesEventosAsociados(new HashSet<PlanEvento> (query.getResultList()));

			PlanInteresPorEvento planInteresPorEvento = disposicion.getPlanInteresDisposicion().getPlanInteresPorDefectoVigente();
			query = getEm().createQuery("SELECT ti FROM TipoInteresFijadoImp ti"+
					" WHERE ti.planInteresPorEvento.id = ?");
			query.setParameter(1, planInteresPorEvento.getId());
			planInteresPorEvento.setTipoInteres(new HashSet<TipoInteresFijado>());
			planInteresPorEvento.addTiposInteres(new HashSet<TipoInteresFijado>(query.getResultList()));

			//Liquidacion intereses manual disposicion
			query = getEm().createQuery("SELECT em FROM LiquidacionInteresesManualDisposicionImp em"+
						" WHERE em.disposicion.id = ? " +
						" and em.fechaEvento < ? and em.esEstadoActivo=?");
			query.setParameter(1, idEvento);
			query.setParameter(2, fechaEjecucion);
			query.setParameter(3, true);
			disposicion.setEventosManuales(new HashSet<EventoManual>());
			disposicion.addEventosManualesToSet(new HashSet<EventoManual>(query.getResultList()));

			if(eventosOperacion!=null)
				eventosOperacion.addEventosManuales(disposicion.getEventosManuales());

			evento = disposicion;

		} else if(evento instanceof AmortizacionManual) {
			query = getEm().createQuery("SELECT amr FROM AmortizacionManualImp amr" +
					" LEFT JOIN FETCH amr.planesEventosAsociados pe " +
					" WHERE amr.id = ? and amr.esEstadoActivo=?");
			query.setParameter(1, idEvento);
			query.setParameter(2, true);
			evento = (Evento) query.getSingleResult();
		}

		Set<EventoAutomatico> eventosDependientes = new HashSet<EventoAutomatico>();
		List<ConEventoAsociado> eventosDependientesTemp = new ArrayList<ConEventoAsociado>();
		query = getEm().createQuery("SELECT c FROM LiquidacionComisionesImp c" +
					" WHERE c.eventoAsociado.id = ?  and c.esEstadoActivo=?");
		query.setParameter(1, idEvento);
		query.setParameter(2, true);
		eventosDependientesTemp.addAll(query.getResultList());

		query = getEm().createQuery("SELECT s FROM SubsidioEventoImp s" +
					" WHERE s.eventoAsociado.id = ? and s.esEstadoActivo=?");
		query.setParameter(1, idEvento);
		query.setParameter(2, true);
		eventosDependientesTemp.addAll(query.getResultList());


		for(ConEventoAsociado eventoTemp : eventosDependientesTemp) {
			ConEventoAsociado eventoDependiente = (ConEventoAsociado)loadEvento(eventoTemp.getId(), eventosOperacion, operacion,fechaEjecucion);
			eventoDependiente.setEventoAsociado(evento);
			if(!eventosDependientes.contains(eventoDependiente)) {
				eventosDependientes.add(eventoDependiente);
			}
		}
		evento.getEventosDependientes().clear();
		evento.getEventosDependientes().addAll(eventosDependientes);

		//Set<CobroEvento> cobros = new HashSet<CobroEvento>();
		evento.getCobros().clear();
		//evento.setCobros(cobros);

		//Incidencia 12137, dejaba mal los saldos por que al momento de recuperar
		//las amortizaciones los recuperaba como cobrado lo cual no deberia ser por
		//que se borran todos los saldos despues de la fechaEjecucion
		query = getEm().createQuery("SELECT c FROM CobroEventoImp c" +
		" WHERE c.eventoAsociado.id = ? AND c.cobroPuntual.fechaCobro < ?");
		query.setParameter(1, idEvento);
		query.setParameter(2, fechaEjecucion);
		evento.getCobros().addAll(query.getResultList());

		if(evento.getCobros().isEmpty())
			evento.getImporteCobrado().setCantidad(BigDecimal.ZERO);

		if(evento.isManual() && eventosOperacion!=null)
			eventosOperacion.addEventoManual((EventoManual) evento);
		else {
			EventoAutomatico eventoAuto = (EventoAutomatico) evento;
			//eventoAuto.setEventosParciales(new HashSet<EventoAutomatico>());
			eventosOperacion.addEventoPlan(eventoAuto.getPlanEvento(), eventoAuto);
			try {
				eventoAuto.setImporteCobrado(new ImporteImp(BigDecimal.ZERO));
			} catch (POJOValidationException e) {
				LOG.error(className + "loadEventoEntidadesLocales(..):" + e.getMessage(), e);
			}
		}

		evento.setOperacion(operacion);

		return evento;
	}

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	private void completaOperacion(Operacion operacion) throws JDBCException {

		operacion.setEventosManuales(new HashSet<EventoManual>());

		//Planes Operacion
		Query query = getEm().createQuery("SELECT pe FROM PlanEventoImp pe" +
				" LEFT JOIN FETCH pe.planInteresPorDefectoVigente " +
				" WHERE pe.activo = ? AND pe.operacionActivo.id = ?" +
				" AND pe.disposicion is null AND pe.amortizacion is null");
		query.setParameter(1, true);
		query.setParameter(2, operacion.getId());
		operacion.setPlanesEventosAsociados(new HashSet<PlanEvento> ());
		operacion.addPlanesEventoToPlanesEventosAsociados(new HashSet<PlanEvento> (query.getResultList()));

		try {
			//Formalización Operacion
			query = getEm().createQuery("SELECT form FROM FormalizacionOperacionImp form" +
					" WHERE form.operacion.id = ?");
			query.setParameter(1, operacion);
			operacion.addEventoManualToSet((FormalizacionOperacion)query.getSingleResult());
		} catch(NoResultException e) {
			//si es una operacion fd avalada puede no tener formalizacion
		}
		if(operacion.getFormalizacionOperacion() != null) {
			//operacion.getFormalizacionOperacion().setEventosDependientes(new HashSet<EventoAutomatico>());
			operacion.getFormalizacionOperacion().getEventosDependientes().clear();
			operacion.getFormalizacionOperacion().getCobros().clear();
		}

		try {
		    //PlanAjustableDias calendario
			query = getEm().createQuery("SELECT pa FROM PlanAjustableDiasImp pa" +
					" LEFT JOIN FETCH pa.calendarios" +
					" WHERE pa.operacion.id = ?");

			query.setParameter(1, operacion.getId());
			operacion.setPlanAjustableDias((PlanAjustableDias)query.getSingleResult());
		} catch(NoResultException e) {
			operacion.setPlanAjustableDias(null);
		}

		try {
			//Calendario ajuste fechas vencimiento
			if(operacion.getPlanAjustableDias() != null &&
			operacion.getPlanAjustableDias().isConCalendario())
			{
				query = getEm().createQuery("SELECT ca FROM CalendarioAjusteFechasVencimientoImp ca" +
						" WHERE ca.planAjustableDias.id = ?");
				query.setParameter(1, operacion.getPlanAjustableDias().getId());
				((PlanAjustableDiasConCalendario) operacion.getPlanAjustableDias()).setCalendarios(
						new HashSet<CalendarioAjusteFechasVencimiento>(query.getResultList()));
			}
		} catch(NoResultException e) {
		}
		
		// Obtenemos las disposiciones para poder calcular los subsidios
		if(operacion.getImporteFormalizado() != null){
			operacion.getImporteTotalFormalizado().setCantidad(getImporteDisposiciones(operacion));
		}

	}


	public String getCodigoHostByOperacion(Long idOperacion) throws Exception {

		StringBuilder sb = new StringBuilder("SELECT codigo_host FROM PA_RELACION_HOST WHERE id_operacion=?");

		Query query = getEm().createNativeQuery(sb.toString());
		query.setParameter(1, idOperacion);

		try {
			return ((String)query.getSingleResult());
		}
		catch(NoResultException e) {
			return null;
		}
	}

	/**
	 * Obtiene los planes de la operacion del evento en caso de tenerlos y de las disposiciones indicadas
	 *
	 * @param operacion
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<PlanEvento> getPlanesEjecucion(Operacion operacion, Date fechaEjecucion,
			Evento evento, EventosOperacion eventosOperacion, List<PlanEvento> planesEjecucion) throws JDBCException {
		
		//Planes Operacion
		Query query = getEm().createQuery("SELECT pe FROM PlanEventoImp pe" +
				" LEFT JOIN FETCH pe.planInteresPorDefectoVigente " +
				" LEFT JOIN FETCH pe.disposicion " +
				" LEFT JOIN FETCH pe.disposicion.operacion " +
				" LEFT JOIN FETCH pe.disposicion.operacion.planesEventosAsociados " +
				" LEFT JOIN FETCH pe.amortizacion " +
				" WHERE pe.activo = ? " +
				" AND pe.operacionActivo.id = ?");
		query.setParameter(1, true);
		query.setParameter(2, operacion.getId());
		operacion.setPlanesEventosAsociados(new HashSet<PlanEvento> ());
		operacion.addPlanesEventoToPlanesEventosAsociados(new HashSet<PlanEvento> (query.getResultList())); 

		//Planes de comision con tarifa
		for(PlanCalendarioComision planCalendarioComision : operacion.getPlanesCalendarioComision())
		{
			StringBuilder sb = new StringBuilder();
			sb.append(" SELECT pc FROM PlanComisionImp pc ");
			if(planCalendarioComision.getPlanComision().getTarifa()!=null){
				sb.append(" JOIN FETCH pc.tarifa ");
			}
			sb.append(" WHERE pc.planCalendarioComision.id = ? ");

			query=getEm().createQuery(sb.toString());
			query.setParameter(1, planCalendarioComision.getId());
			PlanComision planComision = (PlanComision) query.getSingleResult();
			planCalendarioComision.setPlanComision(planComision);
		}
		planesEjecucion.addAll(operacion.getPlanesEventosAsociados());

		//Planes disposicion
		List<Long> idsDisposicion = getIdsDisposicionConEventos(operacion.getId(), fechaEjecucion, operacion.getTipoOperacionActivo().getCodigo());
		if(!idsDisposicion.isEmpty()) {
			for(Long idDisposicion : idsDisposicion) {
				if(evento == null || !idDisposicion.equals(evento.getId())) {
					DisposicionOperacion disposicion = (DisposicionOperacion) loadEvento(idDisposicion, eventosOperacion, operacion,fechaEjecucion); 
					if (!(disposicion instanceof DisposicionTesoreriaImp)) {//ICO-51599. Estaría ok si pasara por aquí para todas las disposiciones pero sólo pasa para la primera ¿?
					     operacion.addEventoManualToSet(disposicion);
					}
				}
			}
		}	

		if(evento != null && evento.getEsEstadoActivo()) {
			if(evento.isManual() && evento.getEsEstadoActivo()) {
				operacion.addEventoManualToSet((EventoManual) evento);
//				if (((EventoManual) evento).isConPlanEvento()) {
//					planesEjecucion.addAll(((ConPlanEvento) evento).getPlanesEventos());
//				}
			}
		}

		//Tipos interes
		for(PlanEvento planEvento : planesEjecucion) {
			if(planEvento.isConPlanInteresPorEvento() && ((ConPlanInteresPorEvento)planEvento).getPlanInteresPorDefectoVigente() != null) {
				PlanInteresPorEvento planInteresPorEvento = ((ConPlanInteresPorEvento)planEvento).getPlanInteresPorDefectoVigente();
				query = getEm().createQuery("SELECT ti FROM TipoInteresFijadoImp ti"+
						" WHERE ti.planInteresPorEvento.id = ?");
				query.setParameter(1, planInteresPorEvento.getId());
				planInteresPorEvento.setTipoInteres(new HashSet<TipoInteresFijado>());
				planInteresPorEvento.addTiposInteres(new HashSet<TipoInteresFijado>(query.getResultList()));
			}
		}
		Collections.sort(planesEjecucion, new PlanEventoPorOrdenComparator());
		
		if(operacion.getImporteFormalizado() != null){
			operacion.getImporteTotalFormalizado().setCantidad(getImporteDisposiciones(operacion));
		}

		return planesEjecucion;
	}

	/**
	 * Obtiene la primera fecha a la que deben cargtarse los saldos para poder generar correctamente
	 * los eventos que utilizan tramos de saldo
	 *
	 * @param fecha
	 * @return fecha a al que es necesario cargar el primer saldo
	 */
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Date getFechaInicioPrimerEvento(Long idOperacion, Date fecha) {
		try {
			Query query = getEm().createNativeQuery("SELECT MIN(FECHA_INICIO) FROM PA_EVENTO " +
					"WHERE ES_ACTIVO=1 AND ID_OPERACION = ? AND FECHA_EVENTO >= ? AND ID_EVENTO_TOTAL IS NULL");
			query.setParameter(1, idOperacion);
			query.setParameter(2, fecha);

			return FechaUtils.truncateDate((Date)query.getSingleResult());

		} catch(NoResultException e) {
			return null;
		}
	}
	
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Date getFechaInicioPrimerEventoSaldos(Long idOperacion, Date fecha) {
		try {
			Query query = getEm().createNativeQuery(" select max(fecha) from ( " +
					" SELECT MIN(FECHA_INICIO) as fecha FROM PA_EVENTO " +
					" WHERE ES_ACTIVO=1 AND ID_OPERACION = ? AND FECHA_EVENTO >= ? AND ID_EVENTO_TOTAL IS NULL" +
					" UNION " +
					" SELECT max(FECHA_cobro) as fecha FROM PA_cobropuntual "+
					" WHERE  ID_OPERACION =? AND FECHA_cobro <? ) ");
			
			query.setParameter(1, idOperacion);
			query.setParameter(2, fecha);
			query.setParameter(3, idOperacion);
			query.setParameter(4, fecha);

			return FechaUtils.truncateDate((Date)query.getSingleResult());

		} catch(NoResultException e) {
			return null;
		}
	}

	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void eliminaCobrosEventos(Operacion operacion, Date fechaEjecucion) {
		String sQueryEventos = "delete from pa_cobroevento where id_cobro in (select id from pa_cobropuntual where id_operacion = ? and fecha_cobro >= ?)";

		Query query = getEm().createNativeQuery(sQueryEventos);

		query.setParameter(1, operacion.getId());
		query.setParameter(2, fechaEjecucion);

		query.executeUpdate();
	}
	
	
	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void eliminaCobrosEventosFromCobro (Operacion operacion, Date fechaEjecucion) {
	    // INI ICO-118218
		String sQueryEventos = "DELETE FROM ( "
				+ "SELECT ce.* "
				+ "FROM pa_cobroevento ce "
				+ "INNER JOIN pa_cobropuntual cp "
				+ "ON cp.id = ce.id_cobro "
				+ "INNER JOIN pa_evento e "
				+ "ON e.id = ce.id_evento_asociado "
				+ "WHERE cp.id_operacion = ? "
				+ "AND cp.fecha_cobro >= ? "
				+ "AND ( "
				+ "    e.fecha_vencimiento_ajustada IS NULL "
				+ "  OR "
				+ "    cp.fecha_cobro > e.fecha_vencimiento_ajustada "
				+ "  OR "
				+ "    e.fecha_evento >= ? "
				+ " ) "
				+ ")";
		// FIN ICO-118218
        Query query = getEm().createNativeQuery(sQueryEventos);
        query.setParameter(1, operacion.getId());
        query.setParameter(2, fechaEjecucion);
        query.setParameter(3, fechaEjecucion);	// ICO-118218
        query.executeUpdate();


	}

	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void eliminaCobrosEventosFromEvento(Operacion operacion, Date fechaEjecucion) {
		String sQueryEventos = " delete from pa_cobroevento where id_evento_asociado in (select id from pa_evento e where id_operacion = ?  " +
				" and fecha_evento >= ?  " +
				" and es_activo=1 and id_evento_total is null )";

		Query query = getEm().createNativeQuery(sQueryEventos);

		query.setParameter(1, operacion.getId());
		query.setParameter(2, fechaEjecucion);

		query.executeUpdate();
	}
	
	
	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void eliminaCobrosEventosFromEventoSubsidios(Operacion operacion, Date fechaEjecucion) {
		String sQueryEventos = " delete from pa_cobroevento where id_evento_asociado in (select id from pa_evento e where id_operacion = ? " +
				" and fecha_evento >= ? and discriminador not in (17) and es_activo=1 and id_evento_total is null ) ";

		Query query = getEm().createNativeQuery(sQueryEventos);
		query.setParameter(1, operacion.getId());
		query.setParameter(2, fechaEjecucion);

		query.executeUpdate();
	}

	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void eliminaCobrosSubsidios(Long idOperacion, Date fecha) throws Exception {
		StringBuilder sb = new StringBuilder("delete from pa_cobropuntual where id_operacion = ? and fecha_cobro >= ? and aplicacion = 'SUBSD' " +
				" and id not in (select id_cobro from pa_cobroevento c, pa_cobropuntual p where p.id = c.id_cobro and p.id_operacion = ?)");

		Query query = getEm().createNativeQuery(sb.toString());

		query.setParameter(1, idOperacion);
		query.setParameter(2, fecha);
		query.setParameter(3, idOperacion);

		query.executeUpdate();
	}

	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void eliminarEventos(List<Long> operaciones) throws Exception {

		String consultaSQL = EventosQueryProvider.queryDeleteEventos(operaciones);

    	try{
    		Query query = getEm().createNativeQuery(consultaSQL);
    		for(int i=0; i<operaciones.size(); i++) {
    			query.setParameter(i+1, operaciones.get(i));
    		}

    		query.executeUpdate();
    	}catch(Exception e){
    		throw e;
    	}
    }
	
	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
    public void eliminarEventosSubsidios(List<Long> operaciones, Date fEjec) throws Exception {

		// Antes se pasaba por parametro la lista operaciones, pero dentro del metodo no se usa. 
		// Aun asi, si hay un placeholder.
		String consultaSQL = EventosQueryProvider.queryDeleteEventosSubsidios();

		Query query = getEm().createNativeQuery(consultaSQL);
		
		query.setParameter(1, operaciones);


		query.executeUpdate();
    }

	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void updateEventosNumEvento(List<Long> operaciones) throws Exception {
		String consultaSQL = EventosQueryProvider.querySearchEventosNumEvento(operaciones);
		try {
			Query query = getEm().createNativeQuery(consultaSQL);
			query.executeUpdate();
			ejecutarProcUpdateSubsidiosNumEvento(operaciones);
		} catch (Exception e) {
			throw e;
		}
	}
	
	protected void ejecutarProcUpdateSubsidiosNumEvento(List<Long> operaciones) {
		if (!operaciones.isEmpty()) {
			StoredProcedureQuery query = getEm().createStoredProcedureQuery("PRC_UPDATE_SUBS_NUM_EVENTOS");
			String cadena = operaciones.stream().map(String::valueOf).collect(Collectors.joining(","));
			query.registerStoredProcedureParameter(1, String.class, ParameterMode.IN);
			query.setParameter(1, cadena);
			query.execute();
		}
	}

//	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
//	public void eliminaSaldosEventos (Long idOperacion, Date fecha) {
//		
//		LogSingleton.getInstance().iniciarCrono();
//		saldosService.eliminaSaldosOpe(idOperacion, fecha);
//		LOG.debug("Eliminación de saldos");
//	}
	
	
	/**
	 * Elimina los saldos de una operacion a partir de una fech
	 * operacion a
	 * na los eventos da
	 * Elimi de una fecha
	 * @param fecha
	 * @param operacion
	 *
	 * @throws EventoVisitorException
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void eliminaSaldosEventos(Long idOperacion, Date fecha, List<Long> idsEventosMantener, MensajeroMediadorService mensajeroMediadorService,
			List<Long> idEventos, boolean isFromCobro, String tipoOperacion)//ICO-65728
		throws JDBCException, EventoVisitorException,Exception {
		eliminaSaldosEventos(idOperacion,fecha,idsEventosMantener, mensajeroMediadorService, idEventos, isFromCobro, false, tipoOperacion); //ICO-65728
//		saldosService.eliminaSaldosOpe(idOperacion, fecha);
//
//		// Eliminación de eventos:
//		String sQueryEventos = "SELECT ID FROM PA_EVENTO WHERE ID_OPERACION = ? AND FECHA_EVENTO >= ?";
//		if(idsEventosMantener != null && !idsEventosMantener.isEmpty()) {
//			StringBuilder sbIdsEventos = new StringBuilder(" AND ID NOT IN (");
//			for(Long idEventoMantener : idsEventosMantener) {
//				sbIdsEventos.append(idEventoMantener + ", ");
//			}
//			sbIdsEventos.replace(sbIdsEventos.lastIndexOf(", "), sbIdsEventos.length()-1, ")");
//			sQueryEventos = sQueryEventos + sbIdsEventos;
//		}
//
//		Query query = getEm().createNativeQuery(sQueryEventos);
//
//		query.setParameter(1, idOperacion);
//		query.setParameter(2, fecha);
//
//		List<BigDecimal> idsEventosRecuperados = query.getResultList();
//		List<Long> idsEventosBaja = new ArrayList<Long>();
//		for(BigDecimal idEvento : idsEventosRecuperados)
//			idsEventosBaja.add(idEvento.longValue());
//
//
//		// Generador de movimientos:
//		// Si borramos físicamente una disposición, se debe indicar el movimiento
//		// Busca entre los elementos a borrar los que son disposiciones y hace un nuevo registro
//		if(!idsEventosBaja.isEmpty()) {
//			String sQueryDisposiciones = sQueryEventos;
//			sQueryDisposiciones += " AND DISCRIMINADOR IN (" +
//			SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador() + ", " +
//			SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador() + ", " +
//			SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador() + ", " +
//			SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador() + ", " +
//			SubtipoEventoEnum.DISPOSICION_TESORERIA.getDiscriminador() +  //ICO-51599
//			" ) ";
//
//			Query queryDisposiciones = getEm().createNativeQuery(sQueryDisposiciones);
//			queryDisposiciones.setParameter(1, idOperacion);
//			queryDisposiciones.setParameter(2, fecha);
//
//			List<BigDecimal> idsDisposiciones = queryDisposiciones.getResultList();
//
//			if(idsDisposiciones != null && !idsDisposiciones.isEmpty()){
//				for (BigDecimal id: idsDisposiciones){
//					DisposicionOperacion disposicion = disposicionJDBC.getDisposicion(id.longValue());
//
//					if ( idEventos != null ){
//						idEventos.addAll(interfazNucleoDAO.insertEventosPA(disposicion.undoMovimientos()));
//					}
//				}
//			}
//		}
//
//		List<Object> parameters = new ArrayList<Object>();
//		if(isFromCobro) {
//			query = getEm().createNativeQuery(prepareQueryDeleteEventosCobro(idOperacion, fecha, idsEventosMantener, parameters));
//		}
//		else {
//			query = getEm().createNativeQuery(prepareQueryDeleteEventos(idOperacion, fecha, idsEventosMantener, parameters)); //ICO-51599 ha cascado aquí
//		}
//		for(int i=0; i<parameters.size(); i++) {
//			query.setParameter(i+1, parameters.get(i));
//		}
//		query.executeUpdate();
//		LOG.info("RegeneraCuadroEventos.eliminaSaldosEventostiempo() de borrado de eventos");
//
//		LOG.debug("Borrado de eventos");

//		if(!idsEventosBaja.isEmpty())
//			mensajeroMediadorService.sendBajasContabilidad(idsEventosBaja, fecha, idOperacion);
	}
	
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void eliminaSaldosEventos(Long idOperacion, Date fecha, List<Long> idsEventosMantener, MensajeroMediadorService mensajeroMediadorService,
			List<Long> idEventos, boolean isFromCobro, boolean prueba, String tipoOperacion) //ICO-65728
		throws JDBCException, EventoVisitorException,Exception {

		Date fechaPrueba=fecha;
		if(prueba){
			fechaPrueba=FechaUtils.sumaUnDiaCalendario(fecha);
		}
		saldosService.eliminaSaldosOpe(idOperacion, fechaPrueba);

		// Eliminación de eventos:
		String sQueryEventos = "SELECT ID FROM PA_EVENTO WHERE ID_OPERACION = ? AND FECHA_EVENTO >= ?";
		if(idsEventosMantener != null && !idsEventosMantener.isEmpty()) {
			StringBuilder sbIdsEventos = new StringBuilder(" AND ID NOT IN (");
			for(Long idEventoMantener : idsEventosMantener) {
				sbIdsEventos.append(idEventoMantener + ", ");
			}
			sbIdsEventos.replace(sbIdsEventos.lastIndexOf(", "), sbIdsEventos.length()-1, ")");
			sQueryEventos = sQueryEventos + sbIdsEventos;
		}

//ICO-62732 Se añade el caso de las comisiones prepagables para que también se eliminen
		String sQueryEventosMasPrepagables = sQueryEventos + " UNION" +
												" SELECT" +
												"    ev.id" +
												" FROM " +
												"    pa_evento ev" +
												" INNER JOIN pa_operacion ope ON ev.id_operacion = ope.id" +
												" INNER JOIN pa_planevento pev ON ev.id_plan_evento = pev.id" +
												" INNER JOIN pa_plan_calendario_comision pcc ON pev.id = pcc.id" +
												" INNER JOIN pa_plan_calculo_comisiones pccc ON pcc.id = pccc.id_plan_cal_comision" +
												" LEFT JOIN pa_cobroevento ce on ce.ID_EVENTO_ASOCIADO=ev.id" +
												" LEFT JOIN PA_COBROPUNTUAL cp on cp.id = ce.ID_COBRO" +
												" WHERE" +
												"    ev.id_operacion = ?" +
												"    AND ev.fechafinvalidez_tipo > ?" +
												"	 AND ope.disc_operacion = 'OP_FD'" +
												"    AND (ce.ID_COBRO is null or cp.FECHA_COBRO >= ?)" +
												"    AND pccc.prepagable = 1"; //ICO-67866 y ICO-68147 Cambio operador '>=' por '>' en fechafinvalidez_tipo. Añadidos LEFT JOIN de pa_cobroevento y pa_cobropuntual y comprobaciones de cobro en WHERE
		
		if(idsEventosMantener != null && !idsEventosMantener.isEmpty()) {
			StringBuilder sbIdsEventos = new StringBuilder(" AND EV.ID NOT IN (");
			for(Long idEventoMantener : idsEventosMantener) {
				sbIdsEventos.append(idEventoMantener + ", ");
			}
			sbIdsEventos.replace(sbIdsEventos.lastIndexOf(", "), sbIdsEventos.length()-1, ")");
			sQueryEventosMasPrepagables = sQueryEventosMasPrepagables + sbIdsEventos;
		}
		
		Query query = getEm().createNativeQuery(sQueryEventosMasPrepagables);
		
		query.setParameter(1, idOperacion);
		query.setParameter(2, fecha);
		query.setParameter(3, idOperacion);
		query.setParameter(4, fecha);
		query.setParameter(5, fecha);//ICO-67866 y ICO-68147

		List<BigDecimal> idsEventosRecuperados = query.getResultList();
		List<Long> idsEventosBaja = new ArrayList<Long>();
		for(BigDecimal idEvento : idsEventosRecuperados)
			idsEventosBaja.add(idEvento.longValue());


		// Generador de movimientos:
		// Si borramos físicamente una disposición, se debe indicar el movimiento
		// Busca entre los elementos a borrar los que son disposiciones y hace un nuevo registro
		if(!idsEventosBaja.isEmpty()) {
			String sQueryDisposiciones = sQueryEventos;
			sQueryDisposiciones += " AND DISCRIMINADOR IN (" +
			SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador() + ", " +
			SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador() + ", " +
			SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador() + ", " +
			SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador() + ", " +
			SubtipoEventoEnum.DISPOSICION_TESORERIA.getDiscriminador() +  //ICO-51599
			" ) ";

			Query queryDisposiciones = getEm().createNativeQuery(sQueryDisposiciones);
			queryDisposiciones.setParameter(1, idOperacion);
			queryDisposiciones.setParameter(2, fecha);

			List<BigDecimal> idsDisposiciones = queryDisposiciones.getResultList();

			if(idsDisposiciones != null && !idsDisposiciones.isEmpty()){
				for (BigDecimal id: idsDisposiciones){
					DisposicionOperacion disposicion = disposicionJDBC.getDisposicion(id.longValue());

					if ( idEventos != null ){
						idEventos.addAll(interfazNucleoDAO.insertEventosPA(disposicion.undoMovimientos()));
					}
				}
			}
		}

		List<Object> parameters = new ArrayList<Object>();
		if(isFromCobro ) {
			query = getEm().createNativeQuery(prepareQueryDeleteEventosCobro(idOperacion, fecha, idsEventosMantener, parameters));
		} else if("VPO".equals(tipoOperacion) && !isFromCobro) {
			query = getEm().createNativeQuery(prepareQueryDeleteEventosSubsidiosAux(idOperacion, fecha, idsEventosMantener, parameters));
		}
		else {
			query = getEm().createNativeQuery(prepareQueryDeleteEventos(idOperacion, fecha, idsEventosMantener, parameters)); //ICO-51599 ha cascado aquí
		}
		for(int i=0; i<parameters.size(); i++) {
			query.setParameter(i+1, parameters.get(i));
		}
		query.executeUpdate();
		
		List<Object> parametersPrepagables = new ArrayList<Object>();
		if(fecha != null) {
			query = getEm().createNativeQuery(prepareQueryDeleteEventosComisionesPrepagablesCobro(idOperacion, fecha, idsEventosMantener, parametersPrepagables));
			for(int i=0; i<parametersPrepagables.size(); i++) {
				query.setParameter(i+1, parametersPrepagables.get(i));
			}
		}
		query.executeUpdate();		
		
		//INI ICO-65728
		if("PS".equals(tipoOperacion)) {
			for(Long idEvento : idsEventosBaja) {
		     	moduloPagosDAO.capturarAsociados(idEvento.toString()); 
	        }
		}
     	//FIN ICO-65728
		
		LOG.info("RegeneraCuadroEventos.eliminaSaldosEventostiempo() de borrado de eventos");

		LOG.debug("Borrado de eventos");

//		if(!idsEventosBaja.isEmpty())
//			mensajeroMediadorService.sendBajasContabilidad(idsEventosBaja, fecha, idOperacion);
	}

	/**
	 * Se recuperan los eventos manuales que afectan al capital pendiente. Cambio realizado para la precedencia de eventos.
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosAfectanCPDia(Operacion operacion, List<Evento> eventosDiaEjecucion, Date fecha, EventosOperacion eventosOperacion,
			Boolean esRecalculoAmortizaciones) {

		Long idOperacion = operacion.getId();

		StringBuilder consultaSQL = new StringBuilder(getModuloInicialQueryMantener());
		consultaSQL.append(" and e.discriminador not in (23,25,26) ");
		consultaSQL.append(" and e.discriminador not in (13, 15) ");
		//No tenga en cuenta los subsidios
		consultaSQL.append(" and e.discriminador not in (53, 54, 55, 56, 63) ");
		consultaSQL.append(" UNION" );
		consultaSQL.append(" SELECT E.ID FROM PA_EVENTO E WHERE E.ID_OPERACION = ? AND (E.DISCRIMINADOR IN(?,?,?,?,?) "); //ICO-91502 no eliminar dev. dispo
		consultaSQL.append("   OR (e.discriminador = ? AND e.manual = 1) OR e.especial = 1) AND E.ES_ACTIVO = 1 "); //ICO-77191 Mantener eventos con mantenimiento especial

		if(esRecalculoAmortizaciones){ //ICO-91502 no eliminar dev. dispo
			consultaSQL.append(" AND ((trunc(fecha_inicio) != ? AND e.discriminador not in(6,7,59)) OR e.manual = 1 OR e.discriminador = 73 OR (e.especial = 1 AND e.discriminador not in(6,7,59)))");
		}
		
		Date fechaInicio=FechaUtils.truncateDate(fecha);
		Date fechaFin=FechaUtils.sumaUnDiaCalendario(fechaInicio);

		Query query = getEm().createNativeQuery(consultaSQL.toString());

		query.setParameter(1,  idOperacion);
		query.setParameter(2,  fechaInicio);
		query.setParameter(3,  fechaFin);
		query.setParameter(4,  fechaInicio);
		query.setParameter(5,  fechaFin);
		query.setParameter(6,  idOperacion);
		query.setParameter(7,  SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(8,  SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
		query.setParameter(9,  SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
		query.setParameter(10,  SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());
		query.setParameter(11,  SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador());
		query.setParameter(12,  SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
	
		if(esRecalculoAmortizaciones){
			query.setParameter(13,  fechaInicio);
		}
		List<BigDecimal> idsEventos = query.getResultList();

		for(BigDecimal idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento.longValue(), eventosOperacion, operacion, fecha);

			/**
			 * (10-10-2011) Los subsidios se vuelven siempre a generar.
			 */
			if(!eventosDiaEjecucion.contains(evento)) {
				evento.setOperacion(operacion);
				eventosDiaEjecucion.add(evento);
			}
			//}
		}

		return eventosDiaEjecucion;
	}
	
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosAfectanCPDiaAmort (Operacion operacion, List<Evento> eventosDiaEjecucion, Date fecha, EventosOperacion eventosOperacion) {

		Long idOperacion = operacion.getId();
		StringBuilder consultaSQL = new StringBuilder(getModuloInicialQueryMantener());
		consultaSQL.append(" UNION" );

		consultaSQL.append(" SELECT E.ID ");
	consultaSQL.append(" FROM PA_EVENTO E WHERE E.ID_OPERACION = ? AND (E.DISCRIMINADOR IN (?, ?, ?, ?, ?, ?, ?, ?,?,?) OR (e.manual =1 or e.especial =1)) AND E.ES_ACTIVO = 1");//ICO-81970
		consultaSQL.append(" AND e.fecha_evento < ?");//ICO-51767
		
		// INI - ICO-52558 - 02/07/2018
		consultaSQL.append(" UNION SELECT E.ID ");
		consultaSQL.append(" FROM PA_EVENTO E WHERE E.ID_OPERACION = ? AND (E.DISCRIMINADOR = ? OR (e.manual =1 or e.especial =1)) AND E.ES_ACTIVO = 1");//ICO-81970
		consultaSQL.append(" AND e.fecha_evento >= ?");
		// FIN - ICO-52558 - 02/07/2018

		Date fechaInicio=FechaUtils.truncateDate(fecha);
		Date fechaFin=FechaUtils.sumaUnDiaCalendario(fechaInicio);

		Query query = getEm().createNativeQuery(consultaSQL.toString());

		query.setParameter(1,  idOperacion);
		query.setParameter(2,  fechaInicio);
		query.setParameter(3,  fechaFin);
		query.setParameter(4,  fechaInicio);
		query.setParameter(5,  fechaFin);		
		query.setParameter(6,  idOperacion);
		query.setParameter(7,  SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(8,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
		query.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_BONOS.getDiscriminador());
		query.setParameter(10,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
		query.setParameter(11,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
		query.setParameter(12,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
		query.setParameter(13,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());  
		//ICO-47093 Se añaden las amortizaciones irregulares para que no se borren al regenerar el cuadro de eventos
		query.setParameter(14,  SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador()); 
		query.setParameter(15,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679
		query.setParameter(16,  SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador()); // ICO-81899

		query.setParameter(17, fechaInicio);//ICO-51767
		
		// INI - ICO-52558 - 02/07/2018
		query.setParameter(18,  idOperacion);
		query.setParameter(19,  SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
		query.setParameter(20, fechaInicio);
		// FIN - ICO-52558 - 02/07/2018
	
		List<BigDecimal> idsEventos = query.getResultList();

		for(BigDecimal idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento.longValue(), eventosOperacion, operacion, fecha);

			/**
			 * (10-10-2011) Los subsidios se vuelven siempre a generar.
			 */
			//if(!evento.getTipoEvento().equals(TipoEventoEnum.LIQUIDACION_SUBSIDIOS)) {
				evento.setOperacion(operacion);
				eventosDiaEjecucion.add(evento);
			//}
		}

		return eventosDiaEjecucion;
	}


	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosAfectanCPDiaBajaAva(Operacion operacion, List<Evento> eventosDiaEjecucion, Date fecha, EventosOperacion eventosOperacion) {

		Long idOperacion = operacion.getId();

		String consultaSQL = getModuloInicialQueryMantener() +
				" UNION " +
				//ICO-81970
				" SELECT E.ID FROM PA_EVENTO E WHERE E.ID_OPERACION = ? AND (E.DISCRIMINADOR = ? OR (e.manual =1 or e.especial =1)) AND E.ES_ACTIVO = 1" +
				" UNION" +
				" SELECT E.ID FROM PA_EVENTO E WHERE E.ID_OPERACION = ? AND E.DISCRIMINADOR IN (?, ?, ?, ?, ?, ?, ?) AND E.ES_ACTIVO = 1" +
				" AND E.fecha_evento < ? " +//ICO-51767
				// INI - ICO-52558 - 02/07/2018
				" UNION SELECT E.ID FROM PA_EVENTO E WHERE E.ID_OPERACION = ? AND E.DISCRIMINADOR = ? AND E.ES_ACTIVO = 1" +
				" AND e.fecha_evento >= ?";
				// FIN - ICO-52558 - 02/07/2018

		Date fechaInicio=FechaUtils.truncateDate(fecha);
		Date fechaFin=FechaUtils.sumaUnDiaCalendario(fechaInicio);
		Query query = getEm().createNativeQuery(consultaSQL);

		query.setParameter(1,  idOperacion);
		query.setParameter(2,  fechaInicio);
		query.setParameter(3,  fechaFin);
		query.setParameter(4,  fechaInicio);
		query.setParameter(5,  fechaFin);		
		query.setParameter(6,  idOperacion);
		query.setParameter(7,  SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(8,  idOperacion);
		query.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
		query.setParameter(10,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_BONOS.getDiscriminador());
		query.setParameter(11,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
		query.setParameter(12,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
		query.setParameter(13,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
		query.setParameter(14,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());
		query.setParameter(15,  SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador());

		query.setParameter(16,  fechaInicio);//ICO-51767
		
		// INI - ICO-52558 - 02/07/2018
		query.setParameter(17,  idOperacion);
		query.setParameter(18,  SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
		query.setParameter(19, fechaInicio);
		// FIN - ICO-52558 - 02/07/2018

		List<BigDecimal> idsEventos = query.getResultList();

		for(BigDecimal idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento.longValue(), eventosOperacion, operacion, fecha);
			/**
			 * (10-10-2011) Los subsidios se vuelven siempre a generar.
			 */
			//if(!evento.getTipoEvento().equals(TipoEventoEnum.LIQUIDACION_SUBSIDIOS)) {
				evento.setOperacion(operacion);
				eventosDiaEjecucion.add(evento);
			//}
		}

		return eventosDiaEjecucion;
	}

	// INI ICO-62994 Modularizar codigo
	private String getModuloInicialQueryMantener() {
		
		StringBuilder consultaSQL = new StringBuilder();
		consultaSQL.append("  SELECT distinct e.id FROM pa_evento e");
		consultaSQL.append(" WHERE e.id_operacion = ? ");
		consultaSQL.append(" and ((e.fecha_evento  >= ? ");
		consultaSQL.append(" and e.fecha_evento < ? ");
		consultaSQL.append(" and (e.discriminador <> 23 or ".concat(isComisionPrepagable(false)).concat("))"));
		consultaSQL.append(" or (e.fechafinvalidez_tipo  >= ? ");
		consultaSQL.append(" and e.fechafinvalidez_tipo < ? ");
		consultaSQL.append(" and (e.discriminador = 23 and ".concat(isComisionPrepagable(true)).concat(")))"));
		consultaSQL.append(" and e.es_activo = 1" );
		consultaSQL.append(" and ((e.id_evento_total is null)");
		consultaSQL.append("   or (e.id_evento_total is not null and e.fecha_evento = ");	//ICO-62994 Mantener tambien eventos parciales si su evento total tambien se mantiene
		consultaSQL.append("   		(select e2.fecha_evento ");								//ICO-62994
		consultaSQL.append("   		 from pa_evento e2");									//ICO-62994
		consultaSQL.append("   		 where e2.es_activo = 1");								//ICO-62994
		consultaSQL.append("   		 and e2.id = e.id_evento_total)))");					//ICO-62994
		consultaSQL.append(" and e.DISCRIMINADOR <> 26" );
		
		return consultaSQL.toString();
	}
	// FIN ICO-62994

	private String isComisionPrepagable (boolean prepagable) {
		
		StringBuilder consultaSQL = new StringBuilder();
		
		consultaSQL.append("(select pcc.id from pa_planevento pe ");
		consultaSQL.append("inner join pa_plan_calculo_comisiones pcc ");
		consultaSQL.append("on pcc.id_plan_cal_comision = pe.id ");
		consultaSQL.append("where pe.id = e.id_plan_evento ");
		consultaSQL.append("and pcc.prepagable = 1) ");
		if(prepagable) {
			consultaSQL.append("is not null ");
		} else {
			consultaSQL.append("is null ");
		}
		
		return consultaSQL.toString();
	}
	
	/**
	 * Comprueba que haya disposiciones de capitalización posteriores a la fecha de ejecución.
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Boolean hayDisposicionesCapitalizacionPosteriores(Operacion operacion, Date fechaEjecucion) {

		Long idOperacion = operacion.getId();

		String consultaSQL = "SELECT e.id FROM pa_evento e"+
				" WHERE e.id_operacion = ? " +
				" and e.fecha_evento >= ? "+
				" and e.discriminador = ? "+
				" and e.es_activo = 1";
		Date fechaEj=FechaUtils.truncateDate(fechaEjecucion);

		Query query = getEm().createNativeQuery(consultaSQL);

		query.setParameter(1,  idOperacion);
		query.setParameter(2,  fechaEj);
		query.setParameter(3,  SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());

		List<BigDecimal> idsEventos = query.getResultList();

		boolean hayDisposiciones=false;
		if(idsEventos!=null){
			if(idsEventos.size()>0)
				hayDisposiciones=true;
		}

		return hayDisposiciones;
	}

	/**
	 * Se recuperan las disposiciones y amortizaciones, ya que no hay que borrarlas al realizar una fijación de tipos
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosMantenerFijacionTipos(Operacion operacion,
			List<Evento> eventosDiaEjecucion, Date fecha, EventosOperacion eventosOperacion,
			boolean isRecalculoDemoras, boolean isRecalculoIntereses) {

		Long idOperacion = operacion.getId();


		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? and trunc(e.fechaEvento) = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.eventoTotal is null"+
				" and e.discriminador not in (53, 54, 55, 63)"+
				" order by id";

		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  idOperacion);
		query.setParameter(2,  fecha);
		query.setParameter(3,  true);


		List<Long> idsEventos = query.getResultList();

		List<Long> idsEventos2 = null;
		
		if(isRecalculoDemoras) { //ICO-60933
			idsEventos2 = getQueryMantenerRestoEventos(SubtipoEventoEnum.getListSubtipoDemora(), idOperacion, fecha);
		}else if(isRecalculoIntereses) { //ICO-66485
			Collection<SubtipoEventoEnum> subtipoEventos = SubtipoEventoEnum.getListSubtipoInteresesAutomatico();
			if(operacion.getTipoOperacion().equals(TipoOperacionActivoEnum.VPO.getCodigo())) {
				subtipoEventos.addAll(SubtipoEventoEnum.getListSubtipoSubsidioVPO());
				subtipoEventos.addAll(SubtipoEventoEnum.getListSubtipoSubsidioVPOCA());
			}
			idsEventos2 = getQueryMantenerRestoEventos(subtipoEventos, idOperacion, fecha); //ICO-76617 Mantener eventos con mantenimiento especial
		} else {
			idsEventos2 = getQueryMantenerEventos(idOperacion, fecha);
		}
		
//		String consultaSQL = "SELECT e.id FROM EventoImp e"+
//		" WHERE e.operacion.id = ? "+
//		" and e.esEstadoActivo = ? "+
//		" and ( e.class in (?,?,?,?,?,?,?,?,?,?,?,?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
//		" OR (e.class = ? AND e.conceptoDemora='DEMORA' AND e.isDemoraManual = 1) ) " +
//		" and to_date(e.fechaEvento, 'DD-MM-RRRR' ) >= ?  " +
//		" order by e.id";

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventosDiaEjecucion.add(evento);
		}
		for(Long idEvento : idsEventos2) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventosDiaEjecucion.add(evento);
		}
		//INI ICO-59393 Comparar con hoy para no recalcular comisiones disponibilidad
		if(operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo())){
            //ICO-63064 campo fechaProrrogaDisp para FAD
            Date fechaLimiteDisposicion = (operacion.getFechaProrrogaDisp() != null)?operacion.getFechaProrrogaDisp():
                                            operacion.getFechaLimiteDisponibilidad();
           
          if(fecha.compareTo(fechaLimiteDisposicion) > 0) { //INI ICO-59393 Comparar con hoy para no recalcular comisiones disponibilidad
          //ICO-63283 Se elimina la validación para que ya no se recalculen las comisiones cuando la fecha de hoy es superior a la fecha límite disposición
          //ICO-62226 Se elimina la validación ya no se recalculen las comisiones cuando el total dispuesto es igual al importe formalizado
            Query query3 = getQueryComisionNoDisp(operacion.getId());
         
            List<BigDecimal> idsEventos3 = query3.getResultList();
         
            for(BigDecimal idEvento : idsEventos3) {
                Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
                evento.setOperacion(operacion);
                eventosDiaEjecucion.add(evento);
            }
          }else {
                //ICO-58721 No eliminar en los recálculos los eventos de comisión por no dispuesto automáticos,
                //cuyo plan de evento no esté activo y que no exista otro plan de comisión por importe no dispuesto activo anterior a la fecha de la comisión
                  Query query4 = getQueryComisionNoDispBajaPlan(operacion);
                 
                    List<BigDecimal> idsEventos4 = query4.getResultList();
                     
                    for(BigDecimal idEvento : idsEventos4) {
                        Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
                        evento.setOperacion(operacion);
                        eventosDiaEjecucion.add(evento);
                    }
            }    
        }
        return eventosDiaEjecucion;
    }

	//ICO-60933
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	private List<Long> getQueryMantenerEventos(Long idOperacion, Date fecha) {
		StringBuilder queryString = new StringBuilder();
		
		queryString.append("SELECT e.id FROM EventoImp e"+
					" WHERE e.operacion.id = ? and trunc(e.fechaEvento) >= ? "+
					" and e.esEstadoActivo = ?"+
					" and ( e.class in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
					" OR (e.class = ? AND e.isDemoraManual = 1) OR e.isMantenimientoEspecial = 1) " + //ICO-76617 Mantener eventos con mantenimiento especial
					" order by e.fechaEvento, e.id");
		
		Query queryResult = getEm().createQuery(queryString.toString());
		
		queryResult.setParameter(1,  idOperacion);
		queryResult.setParameter(2,  fecha);
		queryResult.setParameter(3,  true);
		queryResult.setParameter(4,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
		queryResult.setParameter(5,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_BONOS.getDiscriminador());
		queryResult.setParameter(6,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
		queryResult.setParameter(7,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());
		queryResult.setParameter(8,  SubtipoEventoEnum.AMORTIZACION_CALENDARIO.getDiscriminador());
		queryResult.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_CALENDARIO_FRANCES.getDiscriminador());
		queryResult.setParameter(10,  SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
		queryResult.setParameter(11,  SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
		queryResult.setParameter(12, SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		queryResult.setParameter(13, SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
		queryResult.setParameter(14, SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());
		queryResult.setParameter(15,  SubtipoEventoEnum.SUBVENCION.getDiscriminador());
		queryResult.setParameter(16,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
		queryResult.setParameter(17,  SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador()); //ICO-81899
		queryResult.setParameter(18,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
		//INI - ICO-13713 - 22-09-2014
		queryResult.setParameter(19, SubtipoEventoEnum.LIQUIDACION_COMISION_FLAT.getDiscriminador());
		//ICO-67158 Comision Devolucion Margen
		queryResult.setParameter(20, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOL_MARGEN.getDiscriminador()); //ICO-67158 Comision Devolucion Margen
		queryResult.setParameter(21, SubtipoEventoEnum.LIQUIDACION_COMISION_JEREMIE.getDiscriminador());
		queryResult.setParameter(22, SubtipoEventoEnum.LIQUIDACION_COMISION_APERTURA_MANUAL.getDiscriminador());
		queryResult.setParameter(23, SubtipoEventoEnum.LIQUIDACION_COMISION_ORDINARIA_MANUAL.getDiscriminador());
		queryResult.setParameter(24, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOLUCION.getDiscriminador());
		queryResult.setParameter(25, SubtipoEventoEnum.LIQUIDACION_COMISION_AMORTIZACION_ANTICIPADA_MANUAL.getDiscriminador());
		queryResult.setParameter(26, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_MANUAL.getDiscriminador());
		queryResult.setParameter(27, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_MANUAL.getDiscriminador());
		queryResult.setParameter(28, SubtipoEventoEnum.LIQUIDACION_COMISION_AGENCIA_MANUAL.getDiscriminador());
		queryResult.setParameter(29, SubtipoEventoEnum.AMORTIZACION_CALENDARIO_IRREGULAR.getDiscriminador());
		queryResult.setParameter(30,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679
		queryResult.setParameter(31, SubtipoEventoEnum.LIQUIDACION_COMISION_MOD.getDiscriminador());// ICO-62230 se añade Liq Comisiones MOD 11/09/2020
		//queryResult.setParameter(24, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_ORDINARIA.getDiscriminador());
		//queryResult.setParameter(25, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_ORDINARIA.getDiscriminador());
		//FIN - ICO-13713 - 22-09-2014
		queryResult.setParameter(32, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_OPERACION.getDiscriminador()); //ICO-76617 Se añade liq interes manual
		queryResult.setParameter(33, SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
		
		return queryResult.getResultList();
	}

	//ICO-60933
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Long> getQueryMantenerRestoEventos(Collection<SubtipoEventoEnum> listaTipos, Long idOperacion, Date fecha) {
		StringBuilder queryString = new StringBuilder();
		
		queryString.append(" SELECT e.id FROM EventoImp e "+
				" WHERE e.operacion.id = ? and trunc(e.fechaEvento) >= ? "+
				" and e.esEstadoActivo = ? "+
				" and ( e.class not in ( ");
		
		for(SubtipoEventoEnum tipoEvento : listaTipos) {
			queryString.append(" ?, ");
		}
		
		queryString.deleteCharAt(queryString.lastIndexOf(","));
		queryString.append(" ) OR (e.class = ? AND e.isDemoraManual = 1) OR e.isMantenimientoEspecial = 1) " + //ICO-76617 mantener eventos marcados mant. especial
				" order by e.fechaEvento, e.id ");
		
		Query queryResult = getEm().createQuery(queryString.toString());
		int indiceParam = 4;
		
		queryResult.setParameter(1,  idOperacion);
		queryResult.setParameter(2,  fecha);
		queryResult.setParameter(3,  true);
		
		for(SubtipoEventoEnum tipoEvento : listaTipos) {
			queryResult.setParameter(indiceParam, tipoEvento.getDiscriminador());
			indiceParam++;
		}
		
		queryResult.setParameter(indiceParam, SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
		
		return queryResult.getResultList();
	}

	//ICO-66485
		public List<Long> getQueryMantenerRestoEventosComision(Collection<SubtipoEventoEnum> listaTipos, Long idOperacion, Date fecha) {
			StringBuilder queryString = new StringBuilder();
			
			queryString.append(" SELECT e.id FROM EventoImp e "+
					" WHERE e.operacion.id = ? and trunc(e.fechaEvento) >= ? "+
					" and e.esEstadoActivo = ? "+
					" and ( e.class not in ( ");
			
			for(SubtipoEventoEnum tipoEvento : listaTipos) {
				queryString.append(" ?, ");
			}
			
			queryString.deleteCharAt(queryString.lastIndexOf(","));
			//INI ICO-71205
			queryString.append(" ) OR ");
			
			queryString.append(" ( e.class in ( ");
			for(SubtipoEventoEnum tipoEvento : listaTipos) {
				queryString.append(" ?, ");
			}
			
			queryString.deleteCharAt(queryString.lastIndexOf(","));
			queryString.append(" ) AND e.isMantenimientoEspecial = 1) ");
			//FIN ICO-71205
			queryString.append(" OR (e.class = ? AND e.conceptoDemora='DEMORA' AND e.isDemoraManual = 1) ) " +
			 " order by e.fechaEvento, e.id ");
			
			Query queryResult = getEm().createQuery(queryString.toString());
			int indiceParam = 4;
			
			queryResult.setParameter(1,  idOperacion);
			queryResult.setParameter(2,  fecha);
			queryResult.setParameter(3,  true);
			
			for(SubtipoEventoEnum tipoEvento : listaTipos) {
				queryResult.setParameter(indiceParam, tipoEvento.getDiscriminador());
				indiceParam++;
			}
			//INI ICO-71205
			for(SubtipoEventoEnum tipoEvento : listaTipos) {
				queryResult.setParameter(indiceParam, tipoEvento.getDiscriminador());
				indiceParam++;
			}
			//FIN ICO-71205
			queryResult.setParameter(indiceParam, SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());

			List<Long> idsEventos= queryResult.getResultList();
			
			return idsEventos;
		}
	
	
	/**
	 * Se recuperan liquidaciones de interes manuales anteriores
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public void loadLiquidacionesAnteriores(Operacion operacion,
			Date fecha, EventosOperacion eventosOperacion) {

		Long idOperacion = operacion.getId();

		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? and e.fechaEvento < ? "+
				" and e.esEstadoActivo = ?"+
				" and e.eventoTotal is null"+
				" and e.class in (?,?) ";

		Query query = getEm().createQuery(consultaSQL);
		query.setParameter(1,  idOperacion);
		query.setParameter(2,  fecha);
		query.setParameter(3,  true);
		query.setParameter(4,  SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_OPERACION.getDiscriminador());


		List<Long> idsEventos = query.getResultList();


		consultaSQL = "SELECT e.id FROM pa_evento e"+
				" WHERE e.id_operacion = ? and e.fecha_Evento =" +
				" 											(SELECT MAX(aux.fecha_Evento) FROM pa_evento aux"+
				" 											WHERE aux.id_operacion = ? and aux.fecha_Evento < ?"+
				" 											and aux.es_activo = ?"+
				" 											and aux.ID_EVENTO_TOTAL IS NULL)"+
				" and e.es_activo = ?" +
				" and e.ID_EVENTO_TOTAL IS NULL and e.DISCRIMINADOR in (13,14,15,16) ";

		query = getEm().createNativeQuery(consultaSQL);
		query.setParameter(1,  idOperacion);
		query.setParameter(2,  idOperacion);
		query.setParameter(3,  fecha);
		query.setParameter(4,  true);
		query.setParameter(5,  true);

		try {
			//BigDecimal id=(BigDecimal)query.getSingleResult();
			//idsEventos.add(Long.parseLong(id.toString()));
			//List<Long> ids = ;
			//idsEventos.addAll(query.getResultList());
			List<BigDecimal> lista=query.getResultList();
			for (int i=0; i< lista.size(); i++){
				idsEventos.add(Long.parseLong(lista.get(i).toString()));
			}			
			
		} catch (NoResultException e) {
			//puede no haber liquidaciones anteriores
		}

		if ("VPO".equals(operacion.getTipoOperacionActivo().getCodigo())) {

			consultaSQL = "SELECT ev.id FROM pa_evento ev" 
			+ " WHERE ev.id_operacion = ? and ev.fecha_evento <= ? "
					+ " and ev.es_activo = ?" 
					+ " and ev.ID_EVENTO_TOTAL IS NULL"
					+ " and ev.DISCRIMINADOR in (8,9,10,11,33) order by fecha_evento asc";

			query = getEm().createNativeQuery(consultaSQL);
			query.setParameter(1, idOperacion);
			query.setParameter(2, fecha);
			query.setParameter(3, true);


			try {
				List<BigDecimal> lista=query.getResultList();
				for (int i=0; i< lista.size(); i++){
					idsEventos.add(Long.parseLong(lista.get(i).toString()));
				}
				
			} catch (NoResultException e) {
				// puede no haber liquidaciones anteriores
			}
		}
		
		consultaSQL = "SELECT e.id FROM LiquidacionInteresesAutomaticaDisposicionImp e\n"+
				" WHERE e.operacion.id = ?" +
//				" WHERE e.operacion.id = ? and e.fechaEvento =\n" +
//				" 											(SELECT MAX(aux.fechaEvento) FROM LiquidacionInteresesAutomaticaDisposicionImp aux\n"+
//				" 											WHERE aux.operacion.id = ? and aux.fechaEvento < ?\n"+
//				" 											and aux.esEstadoActivo = ?\n"+
//				" 											and aux.eventoTotal IS NULL)\n"+
				" and e.fechaEvento < ?" +
				" and e.esEstadoActivo = ?\n" +
				" and e.eventoTotal IS NULL\n";

		query = getEm().createQuery(consultaSQL);
		query.setParameter(1,  idOperacion);
//		query.setParameter(2,  idOperacion);
		query.setParameter(2,  fecha);
//		query.setParameter(4,  true);
		query.setParameter(3,  true);

		try {
			List<Long> lista = query.getResultList();
			idsEventos.addAll(lista);
		} catch (NoResultException e) {
			//puede no haber liquidaciones anteriores
		}
		

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
		}
	}

	/**
	 * Recupera los eventos con fecha de evento anterior a la fecha de ejecucion y fecha de mora o
	 * vencimiento ajustada posterior o igual a la fecha de ejecución
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosNoAfectanCapitalizacion(Operacion operacion,
			Date fecha, List<Evento> eventosDiaEjecucion, EventosOperacion eventosOperacion) {

		Long idOperacion = operacion.getId();

		String consultaSQL2 = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? and trunc(e.fechaEvento) = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.class  in (?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
				" and e.eventoTotal is null"+
				" order by id";


		Query query2 = getEm().createQuery(consultaSQL2);

		query2.setParameter(1,  idOperacion);
		query2.setParameter(2,  fecha);
		query2.setParameter(3,  true);
		query2.setParameter(4,  SubtipoEventoEnum.LIQUIDACION_INTERESES.getDiscriminador());
		query2.setParameter(5,  SubtipoEventoEnum.LIQUIDACION_INTERESES_DISPOSICION.getDiscriminador());
		query2.setParameter(6,  SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION.getDiscriminador());
		query2.setParameter(7,  SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_OPERACION.getDiscriminador());
		query2.setParameter(8,  SubtipoEventoEnum.LIQUIDACION_COMISION_AGENCIA.getDiscriminador());
		query2.setParameter(9,  SubtipoEventoEnum.LIQUIDACION_COMISION_AMORTIZACION_ANTICIPADA.getDiscriminador());
		query2.setParameter(10,  SubtipoEventoEnum.LIQUIDACION_COMISION_APERTURA.getDiscriminador());
		query2.setParameter(11,  SubtipoEventoEnum.LIQUIDACION_COMISION_ASEGURAMIENTO.getDiscriminador());
		query2.setParameter(12,  SubtipoEventoEnum.LIQUIDACION_COMISION_ASISTENCIA_TECNICA.getDiscriminador());
		query2.setParameter(13,  SubtipoEventoEnum.LIQUIDACION_COMISION_AVAL.getDiscriminador());
		query2.setParameter(14,  SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD.getDiscriminador());
		query2.setParameter(14,  SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION.getDiscriminador());
		query2.setParameter(15,  SubtipoEventoEnum.LIQUIDACION_COMISION_ESTUDIO.getDiscriminador());
		query2.setParameter(16,  SubtipoEventoEnum.LIQUIDACION_COMISION_GASTOSSUPLIDOS.getDiscriminador());
		query2.setParameter(17,  SubtipoEventoEnum.LIQUIDACION_COMISION_ORDINARIA.getDiscriminador());
		//INI - ICO-13713 - 22-09-2014
		//query2.setParameter(18, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_ORDINARIA.getDiscriminador());
		//query2.setParameter(19, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_ORDINARIA.getDiscriminador());
		//INI - ICO-13713 - 22-09-2014

		List<Long> idsEventos2 = query2.getResultList();

		for(Long idEvento : idsEventos2) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			if(!eventosDiaEjecucion.contains(evento)) {
				evento.setOperacion(operacion);
				eventosDiaEjecucion.add(evento);
			}
		}

		return eventosDiaEjecucion;
	}

	/**
	 * Inserta en Base de Datos todos los eventos actualizados
	 * Inserta en Base de Datos todos los saldos actualizados
	 *
	 * @param operacion
	 * @param saldos
	 * @param eventos
	 * */
	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void insertaSaldosEventos(Operacion operacion, SaldosTotalesOp saldosTotales, List<EventoAutomatico> eventos,
			HashMap<Date, List<Cobro>> cobros, Date fechaDesdeCobroSubsidio, List<Evento> eventosNoBorrados,
			List<CobroEvento> cobrosEventos) throws JDBCException, SQLException {
		Map<String, Saldos> mapaSaldos = new HashMap<>();
		if (eventos != null && !eventos.isEmpty()) {
			// Inserta eventos
			for (EventoAutomatico evento : eventos) {
				// Solo eventos que no se hayan persistido anteriormente
				if (evento.getId() == null || (evento.getFechaEvento().equals(fechaDesdeCobroSubsidio)
						&& evento.getTipoEvento().equals(TipoEventoEnum.LIQUIDACION_SUBSIDIOS))) {
					if (evento.getId() == null) {
						List<Object> parameters = new ArrayList<>();
						Query query = getEm().createNativeQuery(prepareQueryInsertEventos(evento, parameters));
						for (int i = 0; i < parameters.size(); i++) {
							query.setParameter(i + 1, parameters.get(i));
						}
						query.executeUpdate();
					}
					doCobrosEvento(evento, fechaDesdeCobroSubsidio, cobrosEventos);
				}
			}
		}
		// BBN:Por la incidencia 11206
		// incidencia 11716
		if (operacion.getFechaLimiteDisponibilidad() != null) {
			// ICO-63064 campo fechaProrrogaDisp para FAD
			Date fechaLimiteDisposicion = operacion.getFechaLimiteDisponibilidad();
			// ICO-66784-2 campo fechaCancelRemanente para FAD
			if (operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo())) { // ICO-66784
				if (operacion.getFechaCancelRemanente() != null) {
					fechaLimiteDisposicion = operacion.getFechaCancelRemanente();
				} else if (operacion.getFechaProrrogaDisp() != null) {
					fechaLimiteDisposicion = operacion.getFechaProrrogaDisp();
				}
			}
			if (!saldosTotales.getSaldosOperacion().isEmpty()
					&& !saldosTotales.getSaldosOperacion().first().getFechaSaldo().after(fechaLimiteDisposicion)) {
				List<SaldosOp> saldoFechaLimiteDisponi = saldosService.getSaldosOp(operacion.getId(),
						fechaLimiteDisposicion, fechaLimiteDisposicion);
				if (saldoFechaLimiteDisponi.isEmpty()) {
					SaldosOp saldo = saldosTotales.createSaldosOpe(fechaLimiteDisposicion,
							saldosTotales.getSaldosOp(fechaLimiteDisposicion), operacion.getId());
					if (!(operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo()))
							|| (operacion.getTipoOperacionActivo().getCodigo()
									.equals(TipoOperacionActivoEnum.FF.getCodigo())
									&& operacion.getFechaCancelRemanente() != null)) { // ICO-66784
						saldo.fixSaldoDisponible(BigDecimal.ZERO);
					}
					saldosTotales.propagarSaldos(saldo);
				} else {
					saldosTotales.getSaldosOperacion().add(saldoFechaLimiteDisponi.get(0));
				}
			}
		}

		// Inserta los saldos de la operación de tipo operación
		for (Saldos fila : saldosTotales.getSaldosOperacion()) {
			String claveUnica = String.format("%s-%s", String.valueOf(fila.getIdOperacion()),
					String.valueOf(fila.getFechaSaldo().getTime()));
			mapaSaldos.computeIfAbsent(claveUnica, k -> {
				if (fila.getId() != null) {
					getEm().merge(fila);
				} else {
					getEm().persist(fila);
				}
				return fila;
			});
		}

		// Inserta los saldos de la operación de tipo disposición
		if (!saldosTotales.getSaldosDisposiciones().isEmpty()) {
			Collection<SortedSet<SaldosDisp>> enumDis = saldosTotales.getSaldosDisposiciones().values();
			enumDis = eliminaDisposicionesParaSubsidios(enumDis, fechaDesdeCobroSubsidio);
			Iterator<SortedSet<SaldosDisp>> it = enumDis.iterator();
			while (it.hasNext()) {
				SortedSet<SaldosDisp> saldosDisposicion = it.next();
				if (saldosDisposicion != null && !saldosDisposicion.isEmpty()) {
					SaldosDisp sdisp = obtenerPrimerSaldoDisp(saldosDisposicion);
					if (sdisp == null) {
			            continue;
			        }
					List<SaldosDisp> saldosDisps = saldosService.getSaldosDispVPO(sdisp.getIdOperacion(),
							sdisp.getIdDisposicion());
					for (SaldosDisp saldosDisp : saldosDisposicion) {
						String claveUnica = String.format("%s-%s-%s", String.valueOf(saldosDisp.getIdDisposicion()),
								String.valueOf(saldosDisp.getIdOperacion()),
								String.valueOf(saldosDisp.getFechaSaldo().getTime()));
						mapaSaldos.computeIfAbsent(claveUnica, k -> {
							boolean existe = saldosDisps.stream()
									.anyMatch(sd -> String.format("%s-%s-%s", sd.getIdDisposicion(),
											sd.getIdOperacion(), sd.getFechaSaldo().getTime()).equals(claveUnica));
							if (existe) {
								getEm().merge(saldosDisp);
							} else {
								getEm().persist(saldosDisp);
							}
							getEm().flush();
							return saldosDisp;
						});
					}
				}
			}
		}
		if (cobrosEventos != null && !cobrosEventos.isEmpty()) {
			for (CobroEvento cobroEvento : cobrosEventos) {
				getEm().merge(cobroEvento);
			}
		}
	}
	
	private SaldosDisp obtenerPrimerSaldoDisp(SortedSet<SaldosDisp> saldosDisposicion) {
	    return saldosDisposicion != null && !saldosDisposicion.isEmpty() 
	        ? saldosDisposicion.first() 
	        : null;
	}
	
	protected Collection<SortedSet<SaldosDisp>> eliminaDisposicionesParaSubsidios(
			Collection<SortedSet<SaldosDisp>> enumDis, Date fechaDesdeCobroSubsidio) {
			Collection<SortedSet<SaldosDisp>> enumDisResult = enumDis;
			for (SortedSet<SaldosDisp> saldo : enumDisResult) {
			    if (saldo != null && !saldo.isEmpty()) {
			        Iterator<SaldosDisp> iterator = saldo.iterator();
			        while (iterator.hasNext()) {
			            SaldosDisp saldoDisp = iterator.next();
			            if (saldoDisp != null && saldoDisp.getFechaSaldo().before(fechaDesdeCobroSubsidio)) {
			                iterator.remove();
			            }
			        }
			    }
			}
			return enumDisResult;
		}

	//@TransactionAttribute(TransactionAttributeType.REQUIRED)
	public void updateCobroEventos(List<Evento> eventos) throws JDBCException, SQLException {
		if(!eventos.isEmpty()) {
			for(Evento evento : eventos) {
				//getEm().merge(evento);
				if(evento.getEsEstadoActivo() && evento.getAplicacionCobrosEnum()!=null){
					for(CobroEvento cobroEvento :evento.getCobros() ){
						if(cobroEvento.getId()==null){
							getEm().persist(cobroEvento);
						}else{
							getEm().merge(cobroEvento);
						}
					}
				}
			}
		}
	}

	public void doCobrosEvento(Evento evento, Date fechaDesdeCobroSubsidio, List<CobroEvento> cobrosEventos) throws SQLException {

		Operacion operacion = evento.getOperacion();

//		if(evento.getCobros().size() != 0) {
//			for(CobroEvento cobroEvento :evento.getCobros() ){
//				em.persist(cobroEvento);
//
//				Iterator<CobroEvento> it = cobrosEventos.iterator();
//				while(it.hasNext()) {
//					CobroEvento temp = it.next();
//
//					if(temp.getCobroPuntual().getId().equals(cobroEvento.getCobroPuntual().getId())) {
//						if(temp.getEventoAsociado().getId() != null) {
//							if(temp.getEventoAsociado().getId().equals(cobroEvento.getEventoAsociado().getId())) {
//								it.remove();
//							}
//						}
//					}
//				}
//			}
//		}

		if(evento.getTipoEvento().equals(TipoEventoEnum.LIQUIDACION_SUBSIDIOS)) {

			// ICO-44538 - VPO - Tipo de subsidio 2 Porcentaje sobre cuota - Para que no se cree el cobro automático en saldos en VPO.
			if(!evento.getOperacion().getTipoOperacionActivo().equals(TipoOperacionActivoEnum.VPO)){
				
				if( !(Double.parseDouble(evento.getOperacion().getCodigoHost())>Double.parseDouble(Config.get("entidadesLocales2011.codigoHost.min"))
					&& Double.parseDouble(evento.getOperacion().getCodigoHost())< Double.parseDouble(Config.get("entidadesLocales2011.codigoHost.max")))) {

					//Para el caso de préstamos de EELL 2011 no se cobran automáticamente los subsidios
					Cobro cobroPuntual = generaCobroPuntual(operacion, evento);
					em.persist(cobroPuntual);
				}
			
			}
			// FIN ICO-44538 - VPO - Tipo de subsidio 2 Porcentaje sobre cuota
			
		}
	}

	/**
	 * Para el caso de Operaciones LM se dan los subsidios automáticamente por cobrados. Lo mismo pasa en la operación
	 * avalada con las amortizaciones.
	 *
	 * @param operacion
	 * @param evento
	 * @return
	 */
	private Cobro generaCobroPuntual(Operacion operacion, Evento evento) {
		Cobro cobroPuntual = new CobroImp();
		cobroPuntual.setOperacion(operacion);
		if(evento.getTipoEvento().equals(TipoEventoEnum.LIQUIDACION_SUBSIDIOS)) {
			cobroPuntual.setAplicacion(AplicacionCobrosEnum.APLICACION_SUBSIDIO);
		}
		else if(evento.getTipoEvento().equals(TipoEventoEnum.AMORTIZACION)) {
			cobroPuntual.setAplicacion(AplicacionCobrosEnum.APLICACION_AMORTIZACION);
		}
		else if(evento.getTipoEvento().equals(TipoEventoEnum.LIQUIDACION_INTERESES)) {
			cobroPuntual.setAplicacion(AplicacionCobrosEnum.APLICACION_INTERESES);
		}
		if(cobroPuntual.getAplicacion() != null) {
			cobroPuntual.setAuditable(evento.getAuditable());
			CobroEvento cobroEvento = new CobroEventoImp();
			cobroEvento.setEventoAsociado(evento);
			evento.getCobros().add(cobroEvento);
			cobroEvento.setImporteCobrado(evento.getImporte());
			cobroEvento.setCobroPuntual(cobroPuntual);
			cobroPuntual.getCobrosEventos().add(cobroEvento);
			cobroPuntual.setFechaCobro(evento.getFechaEvento());
			//cobroPuntual.setImporteCobrado(evento.getImporte());
			cobroPuntual.setImporte(evento.getImporte());
			cobroPuntual.setTipo(TipoCobroEnum.COBRO_AUTOMATICO.getCodigo());
			//cobroPuntual.setIsCobroFicticio(true);
			cobroPuntual.setFechaCobroEfectiva(evento.getFechaEvento());

			return cobroPuntual;
		} else {
			return null;
		}
	}

	/**
	 * Devuelve los importes cobrados de los eventos de una operación a partir
	 * de una fecha
	 * @throws POJOValidationException
	 * @throws SQLException
	 */
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<SaldosOp> undoCobros(Date fechaEjecucion, Operacion operacion,
			HashMap<Date, List<Cobro>> cobros, List<Long> idsEventosMantener, boolean soloDemoras, boolean isSimulacion) throws SQLException, POJOValidationException {
		List<SaldosOp> saldosExceso = new ArrayList<SaldosOp>();
		HashMap<Long,SaldosOp> saldosExcesoHash = new HashMap<Long,SaldosOp>();

		Collection<Cobro> cobrosPuntuales = cobrosJDBC.searchCobrosPuntualesPorOperacion(operacion, fechaEjecucion, soloDemoras);

		if(isSimulacion) {
			for(List<Cobro> list_temp : cobros.values()) {
				for(Cobro temp : list_temp) {
					cobrosPuntuales.add(temp);
				}
			}
		}

		for(Cobro cobroPuntual : cobrosPuntuales)
		{
		    cobroPuntual.setFechaCobro(cobroPuntual.getFechaCobroEfectiva());
		    
	    	if(cobros.get(FechaUtils.truncateDate(cobroPuntual.getFechaCobro())) == null
	    		&& !cobroPuntual.getAplicacion().equals(TipoEventoEnum.SUBSIDIO_MINISTERIO.getCodigoInterfazContable()) 
	    			&& !cobroPuntual.getAplicacion().equals(TipoEventoEnum.SUBSIDIO_AUTONOMICO.getCodigoInterfazContable())) {
	    		cobros.put(FechaUtils.truncateDate(cobroPuntual.getFechaCobro()), new ArrayList<>());
	    	} 
	    	if(cobroPuntual.getFechaVencimiento() != null && cobros.get(FechaUtils.truncateDate(cobroPuntual.getFechaVencimiento())) == null
	    			&& (cobroPuntual.getAplicacion().equals(TipoEventoEnum.SUBSIDIO_MINISTERIO.getCodigoInterfazContable()) 
	    			|| cobroPuntual.getAplicacion().equals(TipoEventoEnum.SUBSIDIO_AUTONOMICO.getCodigoInterfazContable()))) {
	    		cobros.put(FechaUtils.truncateDate(cobroPuntual.getFechaVencimiento()), new ArrayList<>());
	    	}
	    	
	    	if(!cobroPuntual.getAplicacion().equals(TipoEventoEnum.SUBSIDIO_MINISTERIO.getCodigoInterfazContable()) 
	    				&& !cobroPuntual.getAplicacion().equals(TipoEventoEnum.SUBSIDIO_AUTONOMICO.getCodigoInterfazContable())) {
	    		cobros.get(FechaUtils.truncateDate(cobroPuntual.getFechaCobro())).add(cobroPuntual);
	    	} else if(cobroPuntual.getFechaVencimiento() != null) {
	    		cobros.get(FechaUtils.truncateDate(cobroPuntual.getFechaVencimiento())).add(cobroPuntual);
	    	}

			BigDecimal importeYaCobrado = BigDecimal.ZERO;
			for(CobroEvento cobroEvento : cobroPuntual.getCobrosEventos()) {
				//se resta el importe cobrado de eventos manuales,
				//los eventos automaticos a mantener deben tratarse como si los genererara el cuadro
				if((idsEventosMantener != null && idsEventosMantener.contains(cobroEvento.getEventoAsociado().getId())
						|| cobroEvento.getEventoAsociado().getFechaEvento().before(fechaEjecucion))
					&& cobroEvento.getEventoAsociado().getImporteCobrado().getCantidad() != null
					) {
						if(cobroEvento.getEventoAsociado().isManual() && cobroEvento.getEventoAsociado().getFechaEvento().before(fechaEjecucion)) {
							continue;
						}
						importeYaCobrado =importeYaCobrado.add(cobroEvento.getImporteCobrado().getCantidad());
				}
			}
			SaldosOp saldosOp=null;
			//Se comprueba si ya se ha creado un saldo en exceso para la fecha del cobro
			//este caso es cuando existe un cobro a intereses y amortizaciones en la misma fecha
			// y se regenera desde la fecha de disposicion
			if(saldosExcesoHash.containsKey(cobroPuntual.getFechaCobro().getTime())){
				saldosOp =saldosExcesoHash.get(cobroPuntual.getFechaCobro().getTime()) ;
			}else{
				SaldosTotalesOp saldosTotales = null;
				if(!cobroPuntual.getAplicacion().equals(TipoEventoEnum.SUBSIDIO_MINISTERIO.getCodigoInterfazContable()) 
	    				&& !cobroPuntual.getAplicacion().equals(TipoEventoEnum.SUBSIDIO_AUTONOMICO.getCodigoInterfazContable())) {
					saldosTotales = saldosService.consultaSaldos(cobroPuntual.getOperacion().getId(), cobroPuntual.getFechaCobro());
					saldosOp = saldosTotales.createSaldosOpe(cobroPuntual.getFechaCobro(), saldosTotales.getSaldosOp(cobroPuntual.getFechaCobro()), cobroPuntual.getOperacion().getId());
				} else if(cobroPuntual.getFechaVencimiento() != null) {
					saldosTotales = saldosService.consultaSaldos(cobroPuntual.getOperacion().getId(), cobroPuntual.getFechaVencimiento());
					saldosOp = saldosTotales.createSaldosOpe(cobroPuntual.getFechaVencimiento(), saldosTotales.getSaldosOp(cobroPuntual.getFechaVencimiento()), cobroPuntual.getOperacion().getId());
				}
			}

			if(saldosOp != null) {
				if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_PRELACION.getCodigo())) {
					saldosOp.setSaldoExcesoPrelacion(saldosOp.getSaldoExcesoPrelacion().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
				if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_COMISION_AGENCIA.getCodigo())) {
					saldosOp.setSaldoExcesoComisionAg(saldosOp.getSaldoExcesoComisionAg().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
				if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_COMISIONES.getCodigo())) {
					saldosOp.setSaldoExcesoComisiones(saldosOp.getSaldoExcesoComisiones().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
				if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_DEMORAS.getCodigo())) {
					saldosOp.setSaldoExcesoDemoras(saldosOp.getSaldoExcesoDemoras().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
				if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_INTERESES.getCodigo())) {
					saldosOp.setSaldoExcesoIntereses(saldosOp.getSaldoExcesoIntereses().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
				if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_SUBSIDIO.getCodigo())) {
					saldosOp.setSaldoExcesoSubsidios(saldosOp.getSaldoExcesoSubsidios().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
				if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_SUBVENCION.getCodigo())) {
					saldosOp.setSaldoExcesoSubvenciones(saldosOp.getSaldoExcesoSubvenciones().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
				if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_AMORTIZACION.getCodigo())) {
					saldosOp.setSaldoExcesoAmortizaciones(saldosOp.getSaldoExcesoAmortizaciones().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
	 			if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_GASTOS_SUPLIDOS.getCodigo())) {
					saldosOp.setSaldoExcesoGastosSuplidos(saldosOp.getSaldoExcesoGastosSuplidos().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
	 			//INI - ICO-57486 Comision Provision Fondos - Se anyade discriminador 72
	 			if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_PROVISION_FONDOS.getCodigo())) {
					saldosOp.setSaldoExcesoGastosSuplidos(saldosOp.getSaldoExcesoGastosSuplidos().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
	 			//FIN - ICO-57486 Comision Provision Fondos - Se anyade discriminador 72
	 			if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_CUOTA_CLIENTE.getCodigo())) {
					saldosOp.setSaldoExcesoCuotaCliente(saldosOp.getSaldoExcesoCuotaCliente().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
	 			if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_SUBSIDIO_MINISTERIO.getCodigo())) {
					saldosOp.setSaldoExcesoSubsidioMinisterio(saldosOp.getSaldoExcesoSubsidioMinisterio().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
	 			if(cobroPuntual.getAplicacion().equals(AplicacionCobrosEnum.APLICACION_SUBSIDIO_AUTONOMICO.getCodigo())) {
					saldosOp.setSaldoExcesoSubsidioAutonomico(saldosOp.getSaldoExcesoSubsidioAutonomico().add(cobroPuntual.getImporte().getCantidad().subtract(importeYaCobrado)));
				}
	 			saldosExcesoHash.put(saldosOp.getFechaSaldo().getTime(), saldosOp);
				//saldosExceso.add(saldosOp);
			}
		}

		for (SaldosOp saldo:saldosExcesoHash.values()){
			saldosExceso.add(saldo);
		}

		Collections.sort(saldosExceso, new SaldosFechaComparator());
		return saldosExceso;
	}

	/** Metodos Privados **/
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	private Date getFechaGeneracionSubsidio(Long idOperacion, Date fecha) {
		try {
			Query query = getEm().createNativeQuery("SELECT MAX(FECHA_EVENTO) FROM PA_EVENTO " +
					"WHERE ID_OPERACION = ? AND ES_ACTIVO = 1 AND FECHA_EVENTO < ? AND DISCRIMINADOR IN (?,?,?,?,?,?,?,?,?,?,?)");
			query.setParameter(1, idOperacion);
			query.setParameter(2, fecha);
			query.setParameter(3, SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
			query.setParameter(4, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
			query.setParameter(5, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
			query.setParameter(6, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());
			query.setParameter(7, SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
			query.setParameter(8, SubtipoEventoEnum.SUBVENCION.getDiscriminador());
			query.setParameter(9, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
			query.setParameter(10, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
			query.setParameter(11, SubtipoEventoEnum.AMORTIZACION_CALENDARIO_IRREGULAR.getDiscriminador());
			query.setParameter(12,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679
			query.setParameter(13,  SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador()); //ICO-81899 Anadir evento Devolución Disposición
			
			Date fechaGeneracionSubsidio = (Date) query.getSingleResult();

			return fechaGeneracionSubsidio;

		} catch(NoResultException e) {
			return null;
		}
	}

	private int addParameter(int index, StringBuilder query, List<Object> parameters, Object value) {
		index = query.indexOf("?", index);
		if(value != null)
			parameters.add(value);
		else query.replace(index, index+1, "NULL");

		return index+1;
	}

	private String prepareQueryDeleteEventos(Long idOperacion, Date fecha, List<Long> idEventosMantener, List<Object> parameters) {
		StringBuilder queryDeleteEventosPrepared = new StringBuilder();

		queryDeleteEventosPrepared.append(queryDeleteEventos);

		if(fecha == null) {
			String sFecha = " AND FECHA_EVENTO >= ?";
			int start = queryDeleteEventosPrepared.indexOf(sFecha);
			int end;
			while (start >= 0) {
				end = start+sFecha.length();
				queryDeleteEventosPrepared.delete(start, end);
				start = queryDeleteEventosPrepared.indexOf(sFecha);
			}
		}

		String sIdsEvento = "";
		if(idEventosMantener != null && !idEventosMantener.isEmpty()) {
			StringBuilder sbIdsEventos = new StringBuilder(" AND ID NOT IN (");
			for(Long idEventoMantener : idEventosMantener) {
				sbIdsEventos.append(idEventoMantener + ", ");
			}
			sbIdsEventos.replace(sbIdsEventos.lastIndexOf(", "), sbIdsEventos.length()-1, ")");
			sIdsEvento = sbIdsEventos.toString();
		}

		String sFecha = " AND ID <> ?";
		int start = queryDeleteEventosPrepared.indexOf(sFecha);
		int end;
		while (start >= 0){
			end = start+sFecha.length();
			queryDeleteEventosPrepared.replace(start, end, sIdsEvento);
			start = queryDeleteEventosPrepared.indexOf(sFecha);
		}

		parameters.add(idOperacion);
		if(fecha != null) parameters.add(fecha);
		parameters.add(idOperacion);
		if(fecha != null) parameters.add(fecha);
		parameters.add(idOperacion);
		if(fecha != null) parameters.add(fecha);

		return queryDeleteEventosPrepared.toString();
	}
	
	private String prepareQueryDeleteEventosSubsidiosAux(Long idOperacion, Date fecha, List<Long> idEventosMantener, List<Object> parameters) {
		StringBuilder queryDeleteEventosPreparedSubsidios = new StringBuilder();
		queryDeleteEventosPreparedSubsidios.append("BEGIN  UPDATE PA_EVENTO SET ES_ACTIVO=0 WHERE ID_OPERACION = ? AND FECHA_EVENTO >= ? AND ID <> ? AND DISCRIMINADOR IN(53,54,55,63) ; UPDATE PA_EVENTO SET ES_ACTIVO=0 WHERE ID_OPERACION = ? AND FECHA_EVENTO >= ? AND ID <> ? AND DISCRIMINADOR NOT IN(53,54,55,63)   ; DELETE  FROM PA_COBROPUNTUAL C  WHERE C.TIPO = 2 AND C.ID_OPERACION = ? AND C.FECHA_COBRO > ? ; delete from pa_cobroevento where id_evento_asociado in (select id from pa_evento where id_operacion = ? and fecha_evento > ?); END; ");
		

		if(fecha == null) {
			String sFecha = " AND FECHA_EVENTO >= ?";
			int start = queryDeleteEventosPreparedSubsidios.indexOf(sFecha);
			int end;
			while (start >= 0) {
				end = start+sFecha.length();
				queryDeleteEventosPreparedSubsidios.delete(start, end);
				start = queryDeleteEventosPreparedSubsidios.indexOf(sFecha);
			}
		}

		String sIdsEvento = "";
		if(idEventosMantener != null && !idEventosMantener.isEmpty()) {
			StringBuilder sbIdsEventos = new StringBuilder(" AND ID NOT IN (");
			for(Long idEventoMantener : idEventosMantener) {
				sbIdsEventos.append(idEventoMantener + ", ");
			}
			sbIdsEventos.replace(sbIdsEventos.lastIndexOf(", "), sbIdsEventos.length()-1, ")");
			sIdsEvento = sbIdsEventos.toString();
		}

		String sFecha = " AND ID <> ?";
		int start = queryDeleteEventosPreparedSubsidios.indexOf(sFecha);
		int end;
		while (start >= 0){
			end = start+sFecha.length();
			queryDeleteEventosPreparedSubsidios.replace(start, end, sIdsEvento);
			start = queryDeleteEventosPreparedSubsidios.indexOf(sFecha);
		}

		parameters.add(idOperacion);
		if(fecha != null) parameters.add(fecha);
		parameters.add(idOperacion);
		if(fecha != null) parameters.add(fecha);
		parameters.add(idOperacion);
		if(fecha != null) parameters.add(fecha);
		parameters.add(idOperacion);
		if(fecha != null) parameters.add(fecha);

		return queryDeleteEventosPreparedSubsidios.toString();
	}
	

	private String prepareQueryDeleteEventosCobro(Long idOperacion, Date fecha, List<Long> idEventosMantener, List<Object> parameters) {
		StringBuilder queryDeleteEventosPrepared = new StringBuilder();

		queryDeleteEventosPrepared.append(queryDeleteEventosCobro);

		if(fecha == null) {
			String sFecha = " AND FECHA_EVENTO >= ?";
			int start = queryDeleteEventosPrepared.indexOf(sFecha);
			int end;
			while (start >= 0) {
				end = start+sFecha.length();
				queryDeleteEventosPrepared.delete(start, end);
				start = queryDeleteEventosPrepared.indexOf(sFecha);
			}
		}

		String sIdsEvento = "";
		if(idEventosMantener != null && !idEventosMantener.isEmpty()) {
			StringBuilder sbIdsEventos = new StringBuilder(" AND ID NOT IN (");
			for(Long idEventoMantener : idEventosMantener) {
				sbIdsEventos.append(idEventoMantener + ", ");
			}
			sbIdsEventos.replace(sbIdsEventos.lastIndexOf(", "), sbIdsEventos.length()-1, ")");
			sIdsEvento = sbIdsEventos.toString();
		}

		String sFecha = " AND ID <> ?";
		int start = queryDeleteEventosPrepared.indexOf(sFecha);
		int end;
		while (start >= 0){
			end = start+sFecha.length();
			queryDeleteEventosPrepared.replace(start, end, sIdsEvento);
			start = queryDeleteEventosPrepared.indexOf(sFecha);
		}

		parameters.add(idOperacion);
		if(fecha != null) parameters.add(fecha);

		return queryDeleteEventosPrepared.toString();
	}
	
	private String prepareQueryDeleteEventosComisionesPrepagablesCobro(Long idOperacion, Date fecha, List<Long> idEventosMantener, List<Object> parameters) {
		StringBuilder queryDeleteEventosPrepared = new StringBuilder();
		queryDeleteEventosPrepared.append(queryDeleteEventosComisionesPrepagablesCobro);
		//INI ICO-62732
		String sIdsEvento = "";
		if(idEventosMantener != null && !idEventosMantener.isEmpty()) {
			StringBuilder sbIdsEventos = new StringBuilder(" AND EV.ID NOT IN (");
			for(Long idEventoMantener : idEventosMantener) {
				sbIdsEventos.append(idEventoMantener + ", ");
			}
			sbIdsEventos.replace(sbIdsEventos.lastIndexOf(", "), sbIdsEventos.length()-1, ")");
			sIdsEvento = sbIdsEventos.toString();
		}
		String sFecha = "AND EV.ID <> ?";
		int start = queryDeleteEventosPrepared.indexOf(sFecha);
		int end;
		while (start >= 0){
			end = start+sFecha.length();
			queryDeleteEventosPrepared.replace(start, end, sIdsEvento);
			start = queryDeleteEventosPrepared.indexOf(sFecha);
		}
		//FIN ICO-62732
		parameters.add(idOperacion);
		parameters.add(fecha);
		parameters.add(fecha);//ICO-67866 y ICO-68147
		return queryDeleteEventosPrepared.toString();

	}

	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getRecuperarEventos (Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = "SELECT e.id FROM pa_evento e"+
				" WHERE e.id_operacion = ? "+
				" and e.ES_ACTIVO = 1"+
				" and e.discriminador in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,59,69)  and trunc(e.FECHA_EVENTO) >= ?  " + // ICO-57679 -> 69
				" order by e.id";


		Query query = getEm().createNativeQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  fecha);

		//List<Long> idsEventos = query.getResultList();
		
		List idsEventos =query.getResultList();
		List<Evento> eventos = new ArrayList<Evento>();
		
		for (Object ids : idsEventos) {			
			Evento evento = loadEvento(Long.parseLong(ids.toString()), eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}


		return eventos;
	}
		
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getRecuperarTodosEventos (Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ? and e.eventoTotal is null "+
				" order by e.id";

		Query query = getEm().createQuery(consultaSQL);
		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);

		List<Long> idsEventos = query.getResultList();
		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;
	}
	
	
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getRecuperarSiguientes (Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {

		//ico-13060 se elimina la condición e.eventoTotal is null porque se eliminaban las parciales al hacer un cambio de una parcial a total 
		//en el mantenimiento especial de intereses.
		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ? and e.fechaEvento>=?  "+  //and e.eventoTotal is null 
				" order by e.id";

		Query query = getEm().createQuery(consultaSQL);
		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  fecha);

		List<Long> idsEventos = query.getResultList();
		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;
	}

	
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getSiguientesEventos (Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {

		//se quita condición  and e.id_evento_total is null porque si hay liquidaciones parciales después ls borra y no las vuelve a crear
		String consultaSQL = "SELECT e.id FROM pa_evento e"+
				" WHERE e.id_operacion = ? "+
				" and e.ES_ACTIVO = ? and e.fecha_Evento>=?  and (e.discriminador not in (?,?) OR (e.discriminador=? AND e.manual = 1)) "+ //ICO-76617 Mantener demoras manuales
				" order by e.id";
		
		Query query = getEm().createNativeQuery(consultaSQL);
		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  1);
		query.setParameter(3,  fecha);
		query.setParameter(4,  SubtipoEventoEnum.SUBSIDIO.getDiscriminador());		
		query.setParameter(5,   SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
		query.setParameter(6,   SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());

		//List<Long> idsEventos = query.getResultList();
		
		List idsEventos =query.getResultList();
		List<Evento> eventos = new ArrayList<Evento>();
		
		for (Object ids : idsEventos) {			
			Evento evento = loadEvento(Long.parseLong(ids.toString()), eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}
		
		if(operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo())){
			//ICO-63064 campo fechaProrrogaDisp para FAD
			Date fechaLimiteDisposicion = (operacion.getFechaProrrogaDisp() != null)?operacion.getFechaProrrogaDisp():
											operacion.getFechaLimiteDisponibilidad();	
			
		  if(fecha.compareTo(fechaLimiteDisposicion) > 0 ) { //INI ICO-59393 Comparar con hoy para no recalcular comisiones disponibilidad

			//ICO-63283 Se elimina la validación para que ya no se recalculen las comisiones cuando la fecha de hoy es superior a la fecha límite disposición
			//ICO-62226 Se elimina la validación ya no se recalculen las comisiones cuando el total dispuesto es igual al importe formalizado
			  Query query2 = getQueryComisionNoDisp(operacion.getId());
		  
			  List<BigDecimal> idsEventos2 = query2.getResultList();
		  
			  for(BigDecimal idEvento : idsEventos2) { 
				  Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
				  evento.setOperacion(operacion); 
				  eventos.add(evento); 
			  }
		  }	else {
				//ICO-58721 No eliminar en los recálculos los eventos de comisión por no dispuesto automáticos,
				//cuyo plan de evento no esté activo y que no exista otro plan de comisión por importe no dispuesto activo anterior a la fecha de la comisión
				  Query query3 = getQueryComisionNoDispBajaPlan(operacion);
				  
					List<BigDecimal> idsEventos3 = query3.getResultList();
					  
					for(BigDecimal idEvento : idsEventos3) { 
						Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
						evento.setOperacion(operacion); 
						eventos.add(evento);
					}
			  }
		}
		return eventos;
	}

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosMantenerAltaCobro(Operacion operacion, Date fecha, EventosOperacion eventosOperacion, Cobro cobroPuntual) {


		StringBuilder consultaSQL = new StringBuilder();
		consultaSQL.append("SELECT e.id FROM EventoImp e");
		
		if(cobroPuntual != null && cobroPuntual.getId()!=null)
			consultaSQL.append(" left join fetch  e.cobros ce left join fetch ce.cobroPuntual c ");
	
		consultaSQL.append(" WHERE e.operacion.id = ? ");
		consultaSQL.append(" and e.esEstadoActivo = ? ");
		consultaSQL.append(" and (( e.class in (?,?,?,?,?,?,?,?,?,?,?,?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?,?,?) "); //+1 de ico-51599 +1 de ico-67158 + 1 de ICO-57486
		consultaSQL.append(" OR (e.class = ? AND e.conceptoDemora='DEMORA' AND e.isDemoraManual = 1)  ");//+1 de ICO-81899
		consultaSQL.append(" OR (e.class = ? AND e.condonado = 1) or e.isMantenimientoEspecial = 1) "); //ICO-70386
		consultaSQL.append(" and e.fechaEvento >= ?) ");
		if(cobroPuntual != null && cobroPuntual.getId()!=null) {
			consultaSQL.append(" OR (c.id = "+ (cobroPuntual.getId().toString()));
			consultaSQL.append(" and e.fechaEvento < ?");
			consultaSQL.append(" and e.eventoInfo.fechaFinValidez > ?)");
		}
		if(cobroPuntual != null && cobroPuntual.getId()!=null) {
			consultaSQL.append(" and c.id = " + (cobroPuntual.getId().toString()) +" ");
		}
		
		consultaSQL.append(" order by e.id");
		
				//" and to_date(e.fechaEvento, 'DD-MM-RRRR' ) >= ?  " +

		Query query = getEm().createQuery(consultaSQL.toString());

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
		query.setParameter(4,  SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
		query.setParameter(6,  SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());
		query.setParameter(7,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
		query.setParameter(8,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
		query.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());
		query.setParameter(10, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
		query.setParameter(11, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
		query.setParameter(12, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_BONOS.getDiscriminador());
		query.setParameter(13, SubtipoEventoEnum.SUBVENCION.getDiscriminador());
		query.setParameter(14, SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
		query.setParameter(15, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION.getDiscriminador());
		query.setParameter(16, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_OPERACION.getDiscriminador());
		query.setParameter(17, SubtipoEventoEnum.AMORTIZACION_CALENDARIO.getDiscriminador());
		query.setParameter(18, SubtipoEventoEnum.AMORTIZACION_CALENDARIO_FRANCES.getDiscriminador());
		query.setParameter(19, SubtipoEventoEnum.LIQUIDACION_COMISION_FLAT.getDiscriminador());
		//ICO-67158 Comision Devolucion Margen
		query.setParameter(20, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOL_MARGEN.getDiscriminador());
		query.setParameter(21, SubtipoEventoEnum.LIQUIDACION_COMISION_JEREMIE.getDiscriminador());
		query.setParameter(22, SubtipoEventoEnum.LIQUIDACION_COMISION_APERTURA_MANUAL.getDiscriminador());
		query.setParameter(23, SubtipoEventoEnum.LIQUIDACION_COMISION_ORDINARIA_MANUAL.getDiscriminador());
		query.setParameter(24, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOLUCION.getDiscriminador());
		query.setParameter(25, SubtipoEventoEnum.LIQUIDACION_COMISION_AMORTIZACION_ANTICIPADA_MANUAL.getDiscriminador());
		query.setParameter(26, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_MANUAL.getDiscriminador());
		query.setParameter(27, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_MANUAL.getDiscriminador());
		query.setParameter(28, SubtipoEventoEnum.LIQUIDACION_COMISION_AGENCIA_MANUAL.getDiscriminador());
		query.setParameter(29, SubtipoEventoEnum.LIQUIDACION_COMISION_GASTOSSUPLIDOS_MANUAL.getDiscriminador());
		query.setParameter(30, SubtipoEventoEnum.LIQUIDACION_COMISION_PENALIZACION_FONDOS_NO_APLICADOS.getDiscriminador());
		query.setParameter(31, SubtipoEventoEnum.AMORTIZACION_CALENDARIO_IRREGULAR.getDiscriminador());
		query.setParameter(32, SubtipoEventoEnum.LIQUIDACION_COMISION_DIVIDENDO.getDiscriminador()); //ICO - 47504
		query.setParameter(33, SubtipoEventoEnum.LIQUIDACION_COMISION_REMANENTE.getDiscriminador()); //ICO - 47504
		query.setParameter(34,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679
		

		query.setParameter(35, SubtipoEventoEnum.DISPOSICION_TESORERIA.getDiscriminador()); //ICO-51599 (y las sucesivas posiciones de los parámetros
		query.setParameter(36, SubtipoEventoEnum.LIQUIDACION_COMISION_MOD.getDiscriminador());// ICO-62230 se añade Liq Comisiones MOD 11/09/2020
		
		//INI ICO-57486 se añade en Comisiones - Provision Fondos 26/04/2022
		query.setParameter(37, SubtipoEventoEnum.LIQUIDACION_COMISION_PROVISIONFONDOS_MANUAL.getDiscriminador());
		//FIN ICO-57486 se añade en Comisiones - Provision Fondos 26/04/2022
		
		//ICO-81899 Anadir evento Devolución Disposición
		query.setParameter(38, SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador());
		//ext2150 Convervar las demoras manuales en el recalculo. El discriminador es el mismo. Se diferencian
		//en BBDD por el campo manual de la tabla PA_EVENTO
		query.setParameter(39, SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
		//ICO-43298 Convervar los intereses comprometidos en el recalculo. El discriminador es el mismo. Se diferencian
		//en BBDD por el campo es_condonado de la tabla PA_EVENTO
		query.setParameter(40, SubtipoEventoEnum.LIQUIDACION_INTERESES.getDiscriminador());
		query.setParameter(41,  fecha);
		if(cobroPuntual != null && cobroPuntual.getId()!=null) {
			query.setParameter(42,  fecha);
			query.setParameter(43,  fecha);
		}
		
	
		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		if(operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo())){

			//ICO-63064 campo fechaProrrogaDisp para FAD
			Date fechaLimiteDisposicion = (operacion.getFechaProrrogaDisp() != null)?operacion.getFechaProrrogaDisp():
											operacion.getFechaLimiteDisponibilidad();
			
		  if(fecha.compareTo(fechaLimiteDisposicion) > 0){ //INI ICO-59393 Comparar con hoy para no recalcular comisiones disponibilidad

			//ICO-63283 Se elimina la validación para que ya no se recalculen las comisiones cuando la fecha de hoy es superior a la fecha límite disposición
			//ICO-62226 Se elimina la validación ya no se recalculen las comisiones cuando el total dispuesto es igual al importe formalizado
			Query query2 = getQueryComisionNoDisp(operacion.getId());
		  
			List<BigDecimal> idsEventos2 = query2.getResultList();
		  
			for(BigDecimal idEvento : idsEventos2) { 
				Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
				evento.setOperacion(operacion); 
				eventos.add(evento); 
			}
		  }	else {
			//ICO-58721 No eliminar en los recálculos los eventos de comisión por no dispuesto automáticos,
			//cuyo plan de evento no esté activo y que no exista otro plan de comisión por importe no dispuesto activo anterior a la fecha de la comisión
			  Query query3 = getQueryComisionNoDispBajaPlan(operacion);
			  
				List<BigDecimal> idsEventos3 = query3.getResultList();
				  
				for(BigDecimal idEvento : idsEventos3) { 
					Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
					evento.setOperacion(operacion); 
					eventos.add(evento);
				}
		  }
		}
		
		return eventos;
	}
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getRestoLQIs(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {
		
		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ? "+
				" and e.class = ? " +
				" and e.fechaEvento >= ?  " +
				" and e.condonado = 0" +
				" order by e.id";
		
		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3, SubtipoEventoEnum.LIQUIDACION_INTERESES.getDiscriminador());
		query.setParameter(4,  fecha);
		
		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;
		
	}
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosVariasDisp(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ? "+
				" and ( e.class in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
				" OR (e.class = ? AND e.conceptoDemora='DEMORA' AND e.isDemoraManual = 1)  " +
				" OR (e.class = ? AND e.condonado = 1) ) " +
				//" and to_date(e.fechaEvento, 'DD-MM-RRRR' ) >= ?  " +
				" and e.fechaEvento >= ?  " +
				" order by e.id";

		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
		query.setParameter(4,  SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
		query.setParameter(6,  SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());
		query.setParameter(7,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
		query.setParameter(8,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
		query.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());
		query.setParameter(10, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
		query.setParameter(11, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
		query.setParameter(12, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_BONOS.getDiscriminador());
		query.setParameter(13, SubtipoEventoEnum.SUBVENCION.getDiscriminador());
		query.setParameter(14, SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
		query.setParameter(15, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION.getDiscriminador());
		query.setParameter(16, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_OPERACION.getDiscriminador());
		query.setParameter(17, SubtipoEventoEnum.AMORTIZACION_CALENDARIO.getDiscriminador());
		query.setParameter(18, SubtipoEventoEnum.AMORTIZACION_CALENDARIO_FRANCES.getDiscriminador());
		query.setParameter(19, SubtipoEventoEnum.LIQUIDACION_COMISION_FLAT.getDiscriminador());
		//ICO-67158 Comision Devolucion Margen
		query.setParameter(20, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOL_MARGEN.getDiscriminador());
		query.setParameter(21, SubtipoEventoEnum.LIQUIDACION_COMISION_JEREMIE.getDiscriminador());
		query.setParameter(22, SubtipoEventoEnum.LIQUIDACION_COMISION_APERTURA_MANUAL.getDiscriminador());
		query.setParameter(23, SubtipoEventoEnum.LIQUIDACION_COMISION_ORDINARIA_MANUAL.getDiscriminador());
		query.setParameter(24, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOLUCION.getDiscriminador());
		query.setParameter(25, SubtipoEventoEnum.LIQUIDACION_COMISION_AMORTIZACION_ANTICIPADA_MANUAL.getDiscriminador());
		query.setParameter(26, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_MANUAL.getDiscriminador());
		query.setParameter(27, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_MANUAL.getDiscriminador());
		query.setParameter(28, SubtipoEventoEnum.LIQUIDACION_COMISION_AGENCIA_MANUAL.getDiscriminador());
		query.setParameter(29, SubtipoEventoEnum.LIQUIDACION_COMISION_APERTURA.getDiscriminador());
		query.setParameter(30, SubtipoEventoEnum.LIQUIDACION_INTERESES_DISPOSICION.getDiscriminador());
		query.setParameter(31, SubtipoEventoEnum.LIQUIDACION_COMISION_PENALIZACION_FONDOS_NO_APLICADOS.getDiscriminador());
		query.setParameter(32, SubtipoEventoEnum.AMORTIZACION_CALENDARIO_IRREGULAR.getDiscriminador());
		query.setParameter(33,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679
		query.setParameter(34, SubtipoEventoEnum.LIQUIDACION_COMISION_MOD.getDiscriminador());// ICO-62230 se añade Liq Comisiones MOD 11/09/2020
		query.setParameter(35, SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador()); //ICO-81899 Anadir evento Devolución Disposición
		
		//ext2150 Convervar las demoras manuales en el recalculo. El discriminador es el mismo. Se diferencian
		//en BBDD por el campo manual de la tabla PA_EVENTO
		query.setParameter(36, SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
		//ICO-43298 Convervar los intereses comprometidos en el recalculo. El discriminador es el mismo. Se diferencian
		//en BBDD por el campo es_condonado de la tabla PA_EVENTO
		query.setParameter(37, SubtipoEventoEnum.LIQUIDACION_INTERESES.getDiscriminador());
		query.setParameter(38,  fecha);

		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}
		
		if(operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo())){

			//ICO-63064 campo fechaProrrogaDisp para FAD
			Date fechaLimiteDisposicion = (operacion.getFechaProrrogaDisp() != null)?operacion.getFechaProrrogaDisp():
											operacion.getFechaLimiteDisponibilidad();
			
		  if(fecha.compareTo(fechaLimiteDisposicion) > 0) { //INI ICO-59393 Comparar con hoy para no recalcular comisiones disponibilidad
			//ICO-63283 Se elimina la validación para que ya no se recalculen las comisiones cuando la fecha de hoy es superior a la fecha límite disposición
			//ICO-62226 Se elimina la validación ya no se recalculen las comisiones cuando el total dispuesto es igual al importe formalizado
			  Query query2 = getQueryComisionNoDisp(operacion.getId());
		  
			  List<BigDecimal> idsEventos2 = query2.getResultList();
		  
			  for(BigDecimal idEvento : idsEventos2) { 
				  Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
				  evento.setOperacion(operacion); 
				  eventos.add(evento); 
			  }
		  }	else {
				//ICO-58721 No eliminar en los recálculos los eventos de comisión por no dispuesto automáticos,
				//cuyo plan de evento no esté activo y que no exista otro plan de comisión por importe no dispuesto activo anterior a la fecha de la comisión
				  Query query3 = getQueryComisionNoDispBajaPlan(operacion);
				  
					List<BigDecimal> idsEventos3 = query3.getResultList();
					  
					for(BigDecimal idEvento : idsEventos3) { 
						Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
						evento.setOperacion(operacion); 
						eventos.add(evento);
					}
		  }
		}
		return eventos;
	}

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getAmortAnterioresAltaCobro(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = "";
		
		// INI - ICO-63275 - 14/12/2020
		if(TipoOperacionActivoEnum.EL.getCodigo().equals(operacion.getTipoOperacionActivo().getCodigo())) {
			consultaSQL = "SELECT e.id FROM EventoImp e"+
					" WHERE e.operacion.id = ? "+
					" and e.esEstadoActivo = ?"+
					" and e.class in (?, ?, ?, ?, ?, ?, ?)  and trunc(e.fechaEvento) < ?  " + // ICO-63275 - Se añade AAD
					" order by e.id";
		}else {
			consultaSQL = "SELECT e.id FROM EventoImp e"+
					" WHERE e.operacion.id = ? "+
					" and e.esEstadoActivo = ?"+
					" and e.class in (?, ?, ?, ?, ?, ?, ?)  and trunc(e.fechaEvento) < ?  " + // ICO-63275 - Se añade AAD
					" order by e.id";
		}
		// FIN - ICO-63275 - 14/12/2020


		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
		query.setParameter(4,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_BONOS.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
		query.setParameter(6,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());
		query.setParameter(7, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
		
		// INI - ICO-63275 - 14/12/2020
		if(TipoOperacionActivoEnum.EL.getCodigo().equals(operacion.getTipoOperacionActivo().getCodigo())) {
			query.setParameter(8,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679
			query.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador()); // ICO-63275 - Se añade AAD
			query.setParameter(10,  fecha);
		}else {
			query.setParameter(8,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679
			query.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador()); // ICO-81899
			query.setParameter(10,  fecha);
		}
		
		

		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;
	}

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosMantenerAltaComisiones(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.class not in (?,?,?)  and trunc(e.fechaEvento) >= ? " +
		//		" and e.eventoTotal is null " + ICO-59607 Para no eliminar comisiones parciales
				" order by e.id";


		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
		query.setParameter(4,  SubtipoEventoEnum.SUBSIDIO.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.LIQUIDACION_COMISION_ORDINARIA.getDiscriminador());
		query.setParameter(6,  fecha);


		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;
	}

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosRegeneracionDisposicion(Operacion operacion, Date fecha, EventosOperacion eventosOperacion, boolean isDevolucionFactura) {

		//INI - ICO-53828 - 06-11-2018 - Se modifica la condición que había para filtrar por fecha porque al poner la máscara ('RRRR') no encontraba las disposiciones,
		//además a un tipo fecha no hay que hacerle un TO_DATE como había.
		//se pone el trunc para quitar los segundo y milisegundos que existen en las disposiciones
		StringBuilder consultaSQL = new StringBuilder();
		
		consultaSQL.append("SELECT e.id FROM EventoImp e");
		consultaSQL.append(" WHERE e.operacion.id = ?  and TRUNC(e.fechaEvento) >= ? ");
		consultaSQL.append(" and e.esEstadoActivo = ?");
		consultaSQL.append(" and ((e.class = ? AND e.isDemoraManual = 1) OR (e.class not in(?,?,?) AND e.isMantenimientoEspecial = 1)");
		consultaSQL.append(" OR e.class in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?");
		
		if (isDevolucionFactura) {
			consultaSQL.append(",?,?,?,?,?,?,?,?,?");
		}
		consultaSQL.append(")) order by e.id");
		


		Query query = getEm().createQuery(consultaSQL.toString());

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  fecha);
		query.setParameter(3,  true);
		query.setParameter(4, SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador()); //ICO-77191 no eliminar demoras manuales
		query.setParameter(5, SubtipoEventoEnum.AMORTIZACION_CALENDARIO.getDiscriminador()); //ICO-77191 no eliminar eventos con mantenimiento especial si no son amortizaciones
		query.setParameter(6, SubtipoEventoEnum.AMORTIZACION_CALENDARIO_FRANCES.getDiscriminador()); //ICO-77191
		query.setParameter(7, SubtipoEventoEnum.AMORTIZACION_CALENDARIO_IRREGULAR.getDiscriminador()); //ICO-77191
		
		query.setParameter(8,  SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
		query.setParameter(9,  SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(10,  SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
		query.setParameter(11,  SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());
		query.setParameter(12,  SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador()); //ICO-91502 no eliminar dev. dispo
		//INI - ICO-13713 - 22-09-2014
		query.setParameter(13, SubtipoEventoEnum.LIQUIDACION_COMISION_FLAT.getDiscriminador());
		//ICO-67158 Comision Devolucion Margen
		query.setParameter(14, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOL_MARGEN.getDiscriminador());
		query.setParameter(15, SubtipoEventoEnum.LIQUIDACION_COMISION_JEREMIE.getDiscriminador());
		query.setParameter(16, SubtipoEventoEnum.LIQUIDACION_COMISION_APERTURA_MANUAL.getDiscriminador());
		query.setParameter(17, SubtipoEventoEnum.LIQUIDACION_COMISION_ORDINARIA_MANUAL.getDiscriminador());
		query.setParameter(18, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOLUCION.getDiscriminador());
		query.setParameter(19, SubtipoEventoEnum.LIQUIDACION_COMISION_AMORTIZACION_ANTICIPADA_MANUAL.getDiscriminador());
		query.setParameter(20, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_MANUAL.getDiscriminador());
		query.setParameter(21, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_MANUAL.getDiscriminador());
		query.setParameter(22, SubtipoEventoEnum.LIQUIDACION_COMISION_AGENCIA_MANUAL.getDiscriminador());
		
		query.setParameter(23,  SubtipoEventoEnum.DISPOSICION_TESORERIA.getDiscriminador()); //ICO-51599

		query.setParameter(24, SubtipoEventoEnum.LIQUIDACION_COMISION_MOD.getDiscriminador());// ICO-62230 se añade Liq Comisiones MOD 11/09/2020
		
		//query.setParameter(21, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_ORDINARIA.getDiscriminador());
		//query.setParameter(22, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_ORDINARIA.getDiscriminador());
		//FIN - ICO-13713 - 22-09-2014
		//ICO-77191 Solo mantener amortizaciones en caso de evento AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA
		if (isDevolucionFactura) {
			query.setParameter(25,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
			query.setParameter(26,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
			query.setParameter(27,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());
			query.setParameter(28, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_BONOS.getDiscriminador());
			query.setParameter(29, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
			query.setParameter(30, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
			query.setParameter(31, SubtipoEventoEnum.SUBVENCION.getDiscriminador());
			query.setParameter(32, SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
			query.setParameter(33,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679

		}

		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}
		
		if(operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo())) {
			//ICO-63064 campo fechaProrrogaDisp para FAD
			Date fechaLimiteDisposicion = (operacion.getFechaProrrogaDisp() != null)?operacion.getFechaProrrogaDisp():
											operacion.getFechaLimiteDisponibilidad();
			
		  if(fecha.compareTo(fechaLimiteDisposicion) > 0) { //INI ICO-59393 Comparar con hoy para no recalcular comisiones disponibilidad
			//ICO-63283 Se elimina la validación para que ya no se recalculen las comisiones cuando la fecha de hoy es superior a la fecha límite disposición
			//ICO-62226 Se elimina la validación ya no se recalculen las comisiones cuando el total dispuesto es igual al importe formalizado
			  Query query2 = getQueryComisionNoDisp(operacion.getId());
		  
			  List<BigDecimal> idsEventos2 = query2.getResultList();
		  
			  for(BigDecimal idEvento : idsEventos2) { 
				  Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
				  evento.setOperacion(operacion); 
				  eventos.add(evento); 
			  }
		  }	else {
				//ICO-58721 No eliminar en los recálculos los eventos de comisión por no dispuesto automáticos,
				//cuyo plan de evento no esté activo y que no exista otro plan de comisión por importe no dispuesto activo anterior a la fecha de la comisión
				  Query query3 = getQueryComisionNoDispBajaPlan(operacion);
				  
					List<BigDecimal> idsEventos3 = query3.getResultList();
					  
					for(BigDecimal idEvento : idsEventos3) { 
						Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
						evento.setOperacion(operacion); 
						eventos.add(evento);
					}
		   }
		}
		return eventos;
	}
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEvVariasDisposiciones(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.class in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)  and trunc(e.fechaEvento) >= ?  " +
				" order by e.id";


		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
		query.setParameter(4,  SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
		query.setParameter(6,  SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());
		query.setParameter(7,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
		query.setParameter(8,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
		query.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());
		query.setParameter(10, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_BONOS.getDiscriminador());
		query.setParameter(11, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
		query.setParameter(12, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
		query.setParameter(13, SubtipoEventoEnum.SUBVENCION.getDiscriminador());
		query.setParameter(14, SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
		//INI - ICO-13713 - 22-09-2014
		query.setParameter(15, SubtipoEventoEnum.LIQUIDACION_COMISION_FLAT.getDiscriminador());
		//ICO-67158 Comision Devolucion Margen
		query.setParameter(16, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOL_MARGEN.getDiscriminador());
		query.setParameter(17, SubtipoEventoEnum.LIQUIDACION_COMISION_JEREMIE.getDiscriminador());
		query.setParameter(18, SubtipoEventoEnum.LIQUIDACION_COMISION_APERTURA_MANUAL.getDiscriminador());
		query.setParameter(19, SubtipoEventoEnum.LIQUIDACION_COMISION_ORDINARIA_MANUAL.getDiscriminador());
		query.setParameter(20, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOLUCION.getDiscriminador());
		query.setParameter(21, SubtipoEventoEnum.LIQUIDACION_COMISION_AMORTIZACION_ANTICIPADA_MANUAL.getDiscriminador());
		query.setParameter(22, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_MANUAL.getDiscriminador());
		query.setParameter(23, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_MANUAL.getDiscriminador());
		query.setParameter(24, SubtipoEventoEnum.LIQUIDACION_COMISION_AGENCIA_MANUAL.getDiscriminador());
		
		query.setParameter(25, SubtipoEventoEnum.LIQUIDACION_INTERESES_DISPOSICION.getDiscriminador());
		query.setParameter(26, SubtipoEventoEnum.LIQUIDACION_COMISION_APERTURA.getDiscriminador());
		query.setParameter(27,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679
		query.setParameter(28, SubtipoEventoEnum.LIQUIDACION_COMISION_MOD.getDiscriminador());// ICO-62230 se añade Liq Comisiones MOD 11/09/2020
		query.setParameter(29, SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador()); //ICO-81899 Anadir evento Devolución Disposición
		//FIN - ICO-13713 - 22-09-2014
		query.setParameter(30,  fecha);

		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;
	}

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosMantenerAltaCobroEntidadesLocales(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {

		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.class <> ? " +
				" and e.eventoTotal is null " +
				" and trunc(e.fechaEvento) >= ?  " +
				" order by e.id";


		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
		query.setParameter(4,  fecha);

		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEventoEntidadesLocales(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;
	}


	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosMantenerPlanDemora(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.class <> ?  and trunc(e.fechaEvento) >= ?  " +
				" order by e.id";


		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
		query.setParameter(4,  fecha);

		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;
	}
	
	/**
	 * Se recuperan comisiones manuales, ya que no hay que borrarlas al realizar el alta de una amortizacion
	 * INI - ICO-13713 - 22-09-2014
	 */
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosMantenerComisionesManuales(Operacion operacion,
			List<Evento> eventosDiaEjecucion, Date fecha, EventosOperacion eventosOperacion) {

		Long idOperacion = operacion.getId();

		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? and trunc(e.fechaEvento) >= ? "+
				" and e.esEstadoActivo = ?"+
				" and e.class in (?,?,?,?,?,?,?,?,?,?,?) " +
				" order by e.fechaEvento, e.id";

		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  idOperacion);
		query.setParameter(2,  fecha);
		query.setParameter(3,  true);

		query.setParameter(4, SubtipoEventoEnum.LIQUIDACION_COMISION_FLAT.getDiscriminador());
		//ICO-67158 Comision Devolucion Margen
		query.setParameter(5, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOL_MARGEN.getDiscriminador());
		query.setParameter(6, SubtipoEventoEnum.LIQUIDACION_COMISION_JEREMIE.getDiscriminador());
		query.setParameter(7, SubtipoEventoEnum.LIQUIDACION_COMISION_APERTURA_MANUAL.getDiscriminador());
		query.setParameter(8, SubtipoEventoEnum.LIQUIDACION_COMISION_ORDINARIA_MANUAL.getDiscriminador());
		query.setParameter(9, SubtipoEventoEnum.LIQUIDACION_COMISION_DEVOLUCION.getDiscriminador());
		query.setParameter(10, SubtipoEventoEnum.LIQUIDACION_COMISION_AMORTIZACION_ANTICIPADA_MANUAL.getDiscriminador());
		query.setParameter(11, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_MANUAL.getDiscriminador());
		query.setParameter(12, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_MANUAL.getDiscriminador());
		query.setParameter(13, SubtipoEventoEnum.LIQUIDACION_COMISION_AGENCIA_MANUAL.getDiscriminador());
		query.setParameter(14, SubtipoEventoEnum.LIQUIDACION_COMISION_MOD.getDiscriminador());// ICO-62230 se añade Liq Comisiones MOD 11/09/2020

		//query2.setParameter(24, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD_ORDINARIA.getDiscriminador());
		//query2.setParameter(25, SubtipoEventoEnum.LIQUIDACION_COMISION_UTILIZACION_ORDINARIA.getDiscriminador());


		List<Long> idsEventos = query.getResultList();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventosDiaEjecucion.add(evento);
		}
		
		if(operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo())){
			//ICO-63064 campo fechaProrrogaDisp para FAD
			Date fechaLimiteDisposicion = (operacion.getFechaProrrogaDisp() != null)?operacion.getFechaProrrogaDisp():
											operacion.getFechaLimiteDisponibilidad();
			
		  if(fecha.compareTo(fechaLimiteDisposicion) > 0) { //INI ICO-59393 Comparar con hoy para no recalcular comisiones disponibilidad
			//ICO-63283 Se elimina la validación para que ya no se recalculen las comisiones cuando la fecha de hoy es superior a la fecha límite disposición
			//ICO-62226 Se elimina la validación ya no se recalculen las comisiones cuando el total dispuesto es igual al importe formalizado
			  Query query2 = getQueryComisionNoDisp(operacion.getId());
		  
			  List<BigDecimal> idsEventos2 = query2.getResultList();
		  
			  for(BigDecimal idEvento : idsEventos2) { 
				  Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
				  evento.setOperacion(operacion); 
				  eventosDiaEjecucion.add(evento); 
			  }
		  }	else {
				//ICO-58721 No eliminar en los recálculos los eventos de comisión por no dispuesto automáticos,
				//cuyo plan de evento no esté activo y que no exista otro plan de comisión por importe no dispuesto activo anterior a la fecha de la comisión
				  Query query3 = getQueryComisionNoDispBajaPlan(operacion);
				  
					List<BigDecimal> idsEventos3 = query3.getResultList();
					  
					for(BigDecimal idEvento : idsEventos3) { 
						Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion,fecha);
						evento.setOperacion(operacion); 
						eventosDiaEjecucion.add(evento);
					}
			  }
		}
		return eventosDiaEjecucion;
	}
	//FIN - ICO-13713 - 22-09-2014

	// ICO-46562
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public BigDecimal getImporteDisposiciones(Operacion operacion) {

		List<Evento> listaEventos = null;
		BigDecimal sumaImportes = BigDecimal.ZERO;
		
		String consultaSQL = "SELECT e FROM EventoImp e"+
				" WHERE e.operacion.id = ?"+
				" and e.esEstadoActivo = ?"+
				" and e.class in (?,?,?,?) " +
				" order by e.id";

		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
		query.setParameter(4,  SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
		query.setParameter(6,  SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());

		listaEventos = query.getResultList();
		
		for(Evento evento:listaEventos){
			sumaImportes = sumaImportes.add(evento.getImporte().getCantidad());
		}

		return sumaImportes;
	}
	// FIN ICO-46562

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosManuales(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = "SELECT e.id FROM EventoImp e" +
				" WHERE e.operacion.id = ? and trunc(e.fechaEvento) >= ? " +
				" and e.esEstadoActivo = ?" +
				" and e.class in (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
				" order by e.id";


		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  fecha);
		query.setParameter(3,  true);
		query.setParameter(4,  SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
		query.setParameter(6,  SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
		query.setParameter(7,  SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());
		query.setParameter(8,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());			
		query.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());			
		query.setParameter(10, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());			
		query.setParameter(11, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());				
		query.setParameter(12, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
		query.setParameter(13, SubtipoEventoEnum.SUBVENCION.getDiscriminador());
		query.setParameter(14, SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
		query.setParameter(15, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION.getDiscriminador());
		query.setParameter(16, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_OPERACION.getDiscriminador());
		query.setParameter(17,  SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador()); // ICO-57679
		query.setParameter(18,  SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador()); // ICO-81899
		query.setParameter(19, SubtipoEventoEnum.LIQUIDACION_COMISION_FLAT.getDiscriminador()); // ICO-98851

		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;
	}

	public void actualizaEventosCapitalizados(Set<Evento> eventosCapitalizados) {
		for(Evento eventoCapitalizable : eventosCapitalizados) {
			Query query = getEm().createNativeQuery("UPDATE PA_EVENTO SET ES_CAPITALIZADO = 1 WHERE ID = ?");
			query.setParameter(1, eventoCapitalizable.getId());
			query.executeUpdate();
		}
	}

	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Date getFechaInicioLiqManualIntermedia(Long idOperacion, Date fechaEjecucion) {
		Query query = getEm().createQuery("SELECT liq FROM LiquidacionInteresesManualOperacionImp liq " +
				"WHERE liq.operacion.id = ? AND liq.esEstadoActivo = ? " +
				"AND liq.fechaInicio < ? AND liq.fechaEvento-1 >= ? ");
		query.setParameter(1, idOperacion);
		query.setParameter(2, true);
		query.setParameter(3, fechaEjecucion);
		query.setParameter(4, fechaEjecucion);

		try {
			return ((Evento)query.getSingleResult()).getFechaInicio();
		} catch(NoResultException e) {
			return fechaEjecucion;
		}
	}

	private String prepareQueryInsertEventos(EventoAutomatico evento, List<Object> parameters) {
		StringBuilder queryInsertEventos = new StringBuilder();
		int index = 0;

		queryInsertEventos.append(queryInsertEvento);

		//ID
		Query query = getEm().createNativeQuery("SELECT PA_S_EVENTO.nextVal from DUAL");
		evento.setId(((BigDecimal) query.getResultList().get(0)).longValue());
		index = addParameter(index, queryInsertEventos, parameters, evento.getId());
		//DISCRIMINADOR
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getSubTipoEvento().getDiscriminador());
		//ES_ACTIVO
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getEsEstadoActivo());
		//ID_OPERACION
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getOperacion().getId());
		//ID_PLAN_EVENTO
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getPlanEvento().getId());
		//ID_EVENTO_ASOCIADO
		if(evento instanceof ConEventoAsociado) {
			Evento eventoAsociado = ((ConEventoAsociado) evento).getEventoAsociado();
			index = addParameter(index, queryInsertEventos, parameters,
					eventoAsociado != null ? eventoAsociado.getId() : null);
		}
		else addParameter(index, queryInsertEventos, parameters, null);

		//ID_EVENTO_GENERADOR
		if(evento.getSubTipoEvento().equals(SubtipoEventoEnum.LIQUIDACION_INTERESES_DISPOSICION))
			index = addParameter(index, queryInsertEventos, parameters,
					evento.getPlanEvento().getDisposicion().getId());
		else if(evento.getSubTipoEvento().equals(SubtipoEventoEnum.LIQUIDACION_COMISION_AMORTIZACION_ANTICIPADA))
			index = addParameter(index, queryInsertEventos, parameters,
					evento.getPlanEvento().getAmortizacion().getId());
		else addParameter(index, queryInsertEventos, parameters, null);
		//ID_EVENTO_TOTAL
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getEventoTotal()!=null?
				evento.getEventoTotal().getId():null);

		PrestamosAuditablePOJO auditable = evento.getAuditable();
		//FECHAALTAREGISTRO
		index = addParameter(index, queryInsertEventos, parameters,
				auditable.getFechaAltaRegistro());
		//FECHAMODIFICACIONREGISTRO
		index = addParameter(index, queryInsertEventos, parameters,
				auditable.getFechaModificacionRegistro() != null ?
				auditable.getFechaModificacionRegistro() : null);
		//FECHABAJA
		index = addParameter(index, queryInsertEventos, parameters,
				auditable.getFechaBaja() != null ? auditable.getFechaBaja() : null);
		//USUARIOALTAREGISTRO
		index = addParameter(index, queryInsertEventos, parameters,
				auditable.getUsuarioAltaRegistro());
		//USUARIOMODIFICACIONREGISTRO
		index = addParameter(index, queryInsertEventos, parameters,
				auditable.getUsuarioModificacionRegistro());
		//FECHA_EVENTO
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getFechaEvento() != null ? evento.getFechaEvento() : null);
		//FECHA_MORA
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getFechaMora() != null ? evento.getFechaMora() : null);
		//FECHA_VENCIMIENTO_AJUSTADA
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getFechaVencimientoAjustada() != null ?
				evento.getFechaVencimientoAjustada() : null);
		//IMPORTE_EVENTO
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getImporte().getCantidad());
		//OBSERVACIONES
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getObservaciones());

		EventoInfo eventoInfo = evento.getEventoInfo();
		//FECHAINICIOVALIDEZ_TIPO
		index = addParameter(index, queryInsertEventos, parameters,
				eventoInfo.getFechaInicioValidez() != null ?
				eventoInfo.getFechaInicioValidez() : null);
		//FECHAFINVALIDEZ_TIPO
		index = addParameter(index, queryInsertEventos, parameters,
				eventoInfo.getFechaFinValidez() != null ?
				eventoInfo.getFechaFinValidez() : null);
		//PRECIOPORCENTUAL
		index = addParameter(index, queryInsertEventos, parameters,
				eventoInfo.getPorcentajeAplicado());
		//IMPORTEBASE
		index = addParameter(index, queryInsertEventos, parameters,
				eventoInfo.getImporteBase() != null ?
				eventoInfo.getImporteBase().getCantidad() : null);
		//ES_CAPITALIZADO
		index = addParameter(index, queryInsertEventos, parameters,
				evento instanceof EventoCapitalizable ?
				((EventoCapitalizable) evento).getEsCapitalizado() : null);
		//CUOTA
		index = addParameter(index, queryInsertEventos, parameters,
				evento instanceof AmortizacionAutomaticaFrances ?
				((AmortizacionAutomaticaFrances) evento).getCuota() : null);
		//FECHA_INICIO
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getFechaInicio() != null ?
				evento.getFechaInicio() : null);

		LiquidacionInteresesManual liquidacionManual = evento instanceof LiquidacionInteresesManual ?
				(LiquidacionInteresesManual) evento : null;
		//PORCENTAJE
		index = addParameter(index, queryInsertEventos, parameters,
			liquidacionManual != null ? liquidacionManual.getPorcentaje() : null);
		//FUE_AUTOMATICA
		index = addParameter(index, queryInsertEventos, parameters,
			liquidacionManual != null ? liquidacionManual.getFueAutomatica() : null);
		//CONCEPTO_DEMORA
		index = addParameter(index, queryInsertEventos, parameters,
			evento.getSubTipoEvento().equals(SubtipoEventoEnum.LIQUIDACION_DEMORAS) ?
			((LiqDemoras) evento).getConcepto()  : null);

		//REAJUSTEPORCUOTAS //ICO-81899 Anadir evento Devolución Disposición
		index = addParameter(index, queryInsertEventos, parameters,
				(evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_CALENDARIO) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_CALENDARIO_FRANCES) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_CALENDARIO_IRREGULAR) ||
				evento.getSubTipoEvento().equals(SubtipoEventoEnum.AMORTIZACION_IRREGULAR) )?
						((Amortizacion)evento).getReajustePorCuotas()==null?true:((Amortizacion)evento).getReajustePorCuotas(): true );
		
		//FECHA_VENCIMIENTO_ORIGINAL
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getFechaVencimientoOriginal() != null ?
				evento.getFechaVencimientoOriginal() : null);
		
		//FECHA_FIN_CALCULO ICO-59392
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getFechaFinCalculo() != null ?
				evento.getFechaFinCalculo() : null);		

		//BASE_CALCULO ICO-59392
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getBaseCalculo() != null ?
				evento.getBaseCalculo() : null);
		
		//IMPORTE_CUOTA_CLIENTE ICO-104147
		index = addParameter(index, queryInsertEventos, parameters,
				evento.getImporteCuotaCliente() != null ?
				evento.getImporteCuotaCliente().getCantidad() : null);

		return queryInsertEventos.toString();
	}
	
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getMantenerTodos (Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = " select e.id FROM " + EventoImp.class.getName() + " e " +
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.eventoTotal is null " +
				" order by e.id";


		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);

		List<Long> idsEventos = query.getResultList();
		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEventoAnteriores(idEvento, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;

	}
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosManualesAnteriores(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {
	    boolean isVPO = isVPO(operacion);
	    List<Integer> discriminadores = obtenerDiscriminadores(isVPO);
	    String consultaSQL = construirConsultaSQL(discriminadores, isVPO);
	    
	    Query query = getEm().createNativeQuery(consultaSQL);
	    establecerParametros(query, discriminadores, operacion, fecha, isVPO);
	    
	    List<BigDecimal> idsEventos = query.getResultList();
	    List<Evento> eventos = cargarEventos(idsEventos, operacion, fecha);
	    
	    if (operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.FF.getCodigo())) {
	        agregarEventosComisionNoDisp(operacion, fecha, eventos);
	    }
	    
	    return eventos;
	}

	protected boolean isVPO(Operacion operacion) {
	    return operacion.getTipoOperacionActivo().getCodigo().equals(TipoOperacionActivoEnum.VPO.getCodigo());
	}

	protected List<Integer> obtenerDiscriminadores(boolean isVPO) {
	    List<Integer> discriminadores = new ArrayList<>();
	    discriminadores.add(SubtipoEventoEnum.DISPOSICION_CAPITALIZACION.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.DISPOSICION_NORMAL.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.DISPOSICION_CONDONACION.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.DISPOSICION_REFINANCIACION.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.FORMALIZACION_OPERACION.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.EJECUCION_AVAL.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.CANCELACION_AVAL.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.DOTACION_MICROCREDITO.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.SUBSIDIO.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.SUBSIDIO_CUOTA_MANUAL.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.SUBSIDIO_INTERESES_DISPOSICION_MANUAL.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.SUBSIDIO_INTERESES_MANUAL.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.SUBVENCION.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.PARTIDA_PENDIENTE.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador());
	    discriminadores.add(SubtipoEventoEnum.RETRIBUCION_ESPECIE.getDiscriminador());

	    if (!isVPO) {
	        discriminadores.add(SubtipoEventoEnum.SUBSIDIO_CUOTA.getDiscriminador());
	        discriminadores.add(SubtipoEventoEnum.SUBSIDIO_INTERESES.getDiscriminador());
	        discriminadores.add(SubtipoEventoEnum.SUBSIDIO_INTERESES_DISPOSICION.getDiscriminador());
	    }
	    
	    return discriminadores;
	}

	protected String construirConsultaSQL(List<Integer> discriminadores, boolean isVPO) {
	    StringBuilder consultaSQL = new StringBuilder(" SELECT distinct e.id FROM pa_operacion o ");
	    consultaSQL.append(" INNER JOIN pa_evento e ON e.id_operacion = o.id ");
	    consultaSQL.append(" AND es_activo = ? ");
	    consultaSQL.append(" AND id_evento_total IS NULL ");
	    consultaSQL.append(" AND (discriminador NOT IN (");

	    for (int i = 0; i < discriminadores.size(); i++) {
	        consultaSQL.append("?, ");
	    }
	    consultaSQL.setLength(consultaSQL.length() - 2); // Eliminar la última coma
	    consultaSQL.append(") OR (e.discriminador = ? AND e.manual = 1) OR e.especial = 1) ");
	    consultaSQL.append(" AND e.fecha_evento < ? ");
	    consultaSQL.append(" AND e.id NOT IN (SELECT ev.id FROM pa_evento ev INNER JOIN pa_operacion ope ON ev.id_operacion = ope.id INNER JOIN pa_planevento pev ON ev.id_plan_evento = pev.id INNER JOIN pa_plan_calendario_comision pcc ON pev.id = pcc.id INNER JOIN pa_plan_calculo_comisiones pccc ON pcc.id = pccc.id_plan_cal_comision WHERE ev.id_operacion = ? AND ev.fechafinvalidez_tipo >= ? AND ope.disc_operacion = 'OP_FD' AND pccc.prepagable = 1)");
	    consultaSQL.append(" LEFT JOIN pa_cobroevento ce ON ce.id_evento_asociado = e.id ");
	    consultaSQL.append(" WHERE o.id = ? ");
	    consultaSQL.append(" HAVING (SUM(ce.importe) < e.importe_evento OR SUM(ce.importe) IS NULL) ");
	    consultaSQL.append(" GROUP BY e.id, e.discriminador, e.fecha_evento, e.importe_evento");

	    if (isVPO) {
	        consultaSQL.append(" UNION ");
	        consultaSQL.append(" SELECT e.id FROM pa_evento e ");
	        consultaSQL.append(" INNER JOIN pa_saldos_ope s ON s.id_operacion = e.id_operacion ");
	        consultaSQL.append(" INNER JOIN pa_operacion o ON o.id = e.id_operacion ");
	        consultaSQL.append(" INNER JOIN pa_cobroevento ce ON ce.id_evento_asociado = e.id ");
	        consultaSQL.append(" WHERE o.disc_operacion = 'OP_VPO' ");
	        consultaSQL.append(" AND e.discriminador IN (?, ?, ?, ?) ");
	        consultaSQL.append(" AND e.importe_evento <= (SELECT SUM(ce.importe) FROM pa_cobroevento ce2 WHERE ce2.id_evento_asociado = e.id) ");
	        consultaSQL.append(" AND s.ccvn > 0 ");
	        consultaSQL.append(" AND s.fechasaldo = e.fecha_evento ");
	        consultaSQL.append(" AND e.fecha_evento < ? ");
	        consultaSQL.append(" AND o.id = ? ");
	    }

	    return consultaSQL.toString();
	}

	protected void establecerParametros(Query query, List<Integer> discriminadores, Operacion operacion, Date fecha, boolean isVPO) {
	    int paramIndex = 1;
	    query.setParameter(paramIndex++, true); // es_activo

	    for (Integer discriminador : discriminadores) {
	        query.setParameter(paramIndex++, discriminador);
	    }
	    
	    query.setParameter(paramIndex++, SubtipoEventoEnum.LIQUIDACION_DEMORAS.getDiscriminador());
	    query.setParameter(paramIndex++, fecha);
	    query.setParameter(paramIndex++, operacion.getId());
	    query.setParameter(paramIndex++, fecha);
	    query.setParameter(paramIndex++, operacion.getId());

	    if (isVPO) {
	        query.setParameter(paramIndex++, SubtipoEventoEnum.LIQUIDACION_INTERESES.getDiscriminador());
	        query.setParameter(paramIndex++, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_OPERACION.getDiscriminador());
	        query.setParameter(paramIndex++, SubtipoEventoEnum.LIQUIDACION_INTERESES_DISPOSICION.getDiscriminador());
	        query.setParameter(paramIndex++, SubtipoEventoEnum.LIQUIDACION_INTERESES_MANUAL_DISPOSICION.getDiscriminador());
	        query.setParameter(paramIndex++, fecha);
	        query.setParameter(paramIndex, operacion.getId());
	    }
	}

	protected List<Evento> cargarEventos(List<BigDecimal> idsEventos, Operacion operacion, Date fecha) {
	    List<Evento> eventos = new ArrayList<>();
	    for (BigDecimal idEvento : idsEventos) {
	        Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion, fecha);
	        evento.setOperacion(operacion);
	        eventos.add(evento);
	    }
	    return eventos;
	}

	private void agregarEventosComisionNoDisp(Operacion operacion, Date fecha, List<Evento> eventos) {
	    Date fechaLimiteDisposicion = (operacion.getFechaProrrogaDisp() != null) ? operacion.getFechaProrrogaDisp() : operacion.getFechaLimiteDisponibilidad();
	    if (fecha.compareTo(fechaLimiteDisposicion) > 0) {
	        Query query2 = getQueryComisionNoDisp(operacion.getId());
	        List<BigDecimal> idsEventos2 = query2.getResultList();
	        for (BigDecimal idEvento : idsEventos2) {
	            Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion, fecha);
	            evento.setOperacion(operacion);
	            eventos.add(evento);
	        }
	    } else {
	        Query query3 = getQueryComisionNoDispBajaPlan(operacion);
	        List<BigDecimal> idsEventos3 = query3.getResultList();
	        for (BigDecimal idEvento : idsEventos3) {
	            Evento evento = loadEventoAnteriores(idEvento.longValue(), operacion, fecha);
	            evento.setOperacion(operacion);
	            eventos.add(evento);
	        }
	    }
	}
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getAmortCalendarioAFecha(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {


		String consultaSQL = " select e.id FROM " + EventoImp.class.getName() + " e " +
				" WHERE e.operacion.id = ? and e.fechaEvento = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.class in (?,?,?) " +
				" and e.eventoTotal is null " +
				" order by e.id";

		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  fecha);
		query.setParameter(3,  true);
		query.setParameter(4,  SubtipoEventoEnum.AMORTIZACION_CALENDARIO.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.AMORTIZACION_CALENDARIO_FRANCES.getDiscriminador());
		query.setParameter(6,  SubtipoEventoEnum.AMORTIZACION_CALENDARIO_IRREGULAR.getDiscriminador());


		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEventoAnteriores(idEvento, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

		return eventos;

	}
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getAmortizacionesAnteriores(Operacion operacion, Date fecha, EventosOperacion eventosOperacion) {

		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				operacion.getId().toString() )) {
			LOG_ICO_62556.info("Inicializa getAmortizacionesAnteriores");
		}

		String consulta = " SELECT e.id FROM " + EventoImp.class.getName() + " e " +
							 " WHERE e.operacion.id = ? and e.fechaEvento < ? "+
							 " AND e.esEstadoActivo = ?"+
							 " AND e.class in (?,?,?,?,?,?,?,?,?,?,?,?) " +
							 " AND e.eventoTotal is null " +
							 " order by e.id";


		Query query = getEm().createQuery(consulta);
		
		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  fecha);
		query.setParameter(3,  true);
		query.setParameter(4,  SubtipoEventoEnum.AMORTIZACION_CALENDARIO.getDiscriminador());
		query.setParameter(5,  SubtipoEventoEnum.AMORTIZACION_CALENDARIO_FRANCES.getDiscriminador());
		query.setParameter(6,  SubtipoEventoEnum.AMORTIZACION_IRREGULAR.getDiscriminador());
		query.setParameter(7,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_VOLUNTARIA.getDiscriminador());
		query.setParameter(8,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_AUTORIZADA.getDiscriminador());
		query.setParameter(9,  SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_OBLIGATORIA.getDiscriminador());
		query.setParameter(10, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_REINTEGRO.getDiscriminador());
		query.setParameter(11, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
		query.setParameter(12, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_BONOS.getDiscriminador());
		query.setParameter(13, SubtipoEventoEnum.AMORTIZACION_CALENDARIO_IRREGULAR.getDiscriminador());
		query.setParameter(14, SubtipoEventoEnum.AMORTIZACION_CONDONACION_FINALIZACION.getDiscriminador());
		query.setParameter(15, SubtipoEventoEnum.AMORTIZACION_DEVOLUCION_DISPOSICION.getDiscriminador()); //ICO-81899


		List<Long> idsEventos = query.getResultList();

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEventoAnteriores(idEvento, operacion, fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}
		
		if(LogSingletonICO62556.actualizarTrazaICO62556(
				null,
				null,
				operacion.getId().toString() )) {
			LOG_ICO_62556.info("Finaliza getAmortizacionesAnteriores");
		}

		return eventos;

	}

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Evento loadEventoAnteriores (Long idEvento,Operacion operacion, Date fechaEjecucion) throws JDBCException {

//		Query query = getEm().createQuery("SELECT e FROM EventoImp e" +
//				" LEFT JOIN FETCH e.eventoAsociado " +
//				" WHERE e.id = ?");
//		query.setParameter(1, idEvento);
//		Evento evento = (Evento) query.getSingleResult();

		Query query = getEm().createQuery("SELECT e FROM EventoImp e" +
			" WHERE e.id = ?");
		query.setParameter(1, idEvento);
		Evento evento = (Evento) query.getSingleResult();

		if(evento instanceof LiquidacionInteresesAutomaticaDisposicionImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.liquidacionIntereses.LiquidacionInteresesAutomaticaDisposicionImp e" +
					" LEFT JOIN FETCH e.disposicion " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}

		if(evento instanceof LiquidacionComisionesImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.comisiones.LiquidacionComisionesImp e" +
					" LEFT JOIN FETCH e.eventoAsociado " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}
		if(evento instanceof SubsidioEventoImp) {
			query = getEm().createQuery("SELECT e FROM es.ico.prestamos.entidad.operacion.subsidios.SubsidioEventoImp e" +
					" LEFT JOIN FETCH e.eventoAsociado " +
					" WHERE e.id = ?");
			query.setParameter(1, idEvento);
			evento = (Evento) query.getSingleResult();
		}

		if(evento instanceof AmortizacionManual) {
			query = getEm().createQuery("SELECT amr FROM AmortizacionManualImp amr" +
					" LEFT JOIN FETCH amr.planesEventosAsociados pe " +
					" WHERE amr.id = ?  and amr.esEstadoActivo=?");
			query.setParameter(1, idEvento);
			query.setParameter(2, true);
			evento = (Evento) query.getSingleResult();
		}

		Set<EventoAutomatico> eventosDependientes = new HashSet<EventoAutomatico>();
		List<ConEventoAsociado> eventosDependientesTemp = new ArrayList<ConEventoAsociado>();
		query = getEm().createQuery("SELECT c FROM LiquidacionComisionesImp c" +
					" WHERE c.eventoAsociado.id = ? and c.esEstadoActivo=?");
		query.setParameter(1, idEvento);
		query.setParameter(2, true);
		eventosDependientesTemp.addAll(query.getResultList());

		query = getEm().createQuery("SELECT s FROM SubsidioEventoImp s" +
					" WHERE s.eventoAsociado.id = ? and s.esEstadoActivo=?");
		query.setParameter(1, idEvento);
		query.setParameter(2, true);
		eventosDependientesTemp.addAll(query.getResultList());


		for(ConEventoAsociado eventoTemp : eventosDependientesTemp) {
			ConEventoAsociado eventoDependiente = (ConEventoAsociado)loadEventoAnteriores(eventoTemp.getId(),operacion,fechaEjecucion);
			eventoDependiente.setEventoAsociado(evento);
			if(!eventosDependientes.contains(eventoDependiente)) {
				eventosDependientes.add(eventoDependiente);
			}
		}
		evento.getEventosDependientes().clear();
		evento.getEventosDependientes().addAll(eventosDependientes);

		//Set<CobroEvento> cobros = new HashSet<CobroEvento>();
		evento.getCobros().clear();

		//Incidencia 12137, dejaba mal los saldos por que al momento de recuperar
		//las amortizaciones los recuperaba como cobrado lo cual no deberia ser por
		//que se borran todos los saldos despues de la fechaEjecucion
		query = getEm().createQuery("SELECT c FROM CobroEventoImp c" +
		" WHERE c.eventoAsociado.id = ? AND c.cobroPuntual.fechaCobro < ?");
		query.setParameter(1, idEvento);
		query.setParameter(2, fechaEjecucion);
		evento.getCobros().addAll(query.getResultList());

		if(evento.getCobros().isEmpty())
			evento.getImporteCobrado().setCantidad(BigDecimal.ZERO);

		evento.setOperacion(operacion);

		return evento;
	}

	static{

		StringBuilder sbEvento= new StringBuilder();

		sbEvento.append(" INSERT INTO PA_EVENTO(ID, DISCRIMINADOR, ES_ACTIVO, ID_OPERACION, ID_PLAN_EVENTO, ID_EVENTO_ASOCIADO,\n");
		sbEvento.append(" ID_EVENTO_GENERADOR, ID_EVENTO_TOTAL, FECHAALTAREGISTRO, FECHAMODIFICACIONREGISTRO, FECHABAJA,\n");
		sbEvento.append(" USUARIOALTAREGISTRO, USUARIOMODIFICACIONREGISTRO, FECHA_EVENTO, FECHA_MORA, FECHA_VENCIMIENTO_AJUSTADA,\n");
		sbEvento.append(" IMPORTE_EVENTO, OBSERVACIONES, FECHAINICIOVALIDEZ_TIPO, FECHAFINVALIDEZ_TIPO, PRECIOPORCENTUAL,\n");
		sbEvento.append(" IMPORTEBASE, ES_CAPITALIZADO, CUOTA, FECHA_INICIO, PORCENTAJE, FUE_AUTOMATICA, CONCEPTO_DEMORA, REAJUSTEPORCUOTAS,\n");
		sbEvento.append(" FECHA_VENCIMIENTO_ORIGINAL,FECHA_FIN_CALCULO,BASE_CALCULO,IMPORTE_CUOTA_CLIENTE)\n"); //ICO-59392 ICO-104147
		sbEvento.append(" VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)\n");

		queryInsertEvento = sbEvento.toString();
	}

	static{

		StringBuilder sbEvento= new StringBuilder();

		sbEvento.append("BEGIN ");

		sbEvento.append(" UPDATE PA_EVENTO SET ES_ACTIVO=0 WHERE ID_OPERACION = ? AND FECHA_EVENTO >= ? AND ID <> ?  ;");

		sbEvento.append(" DELETE ");
		sbEvento.append(" FROM PA_COBROPUNTUAL C ");
		sbEvento.append(" WHERE C.TIPO = 2");
		sbEvento.append(" AND C.ID_OPERACION = ?");
		sbEvento.append(" AND C.FECHA_COBRO > ? ;");

		//REVISAR PRUEBA
		sbEvento.append(" delete from pa_cobroevento where id_evento_asociado in (select id from pa_evento where id_operacion = ? and fecha_evento >= ?); ");

		sbEvento.append("END; ");

		queryDeleteEventos = sbEvento.toString();
	}

	static{

		StringBuilder sbEvento= new StringBuilder();

		sbEvento.append("BEGIN ");

		//CAMBIO GORDO
		sbEvento.append(" UPDATE PA_EVENTO SET ES_ACTIVO=0 WHERE ID_OPERACION = ? AND FECHA_EVENTO >= ? AND ID <> ?  ;");

		Collection<TipoCobroEnum>cobrosFicticios= TipoCobroEnum.getTiposCobroFicticio();
		StringBuilder filtroTipoCobro=new StringBuilder();

		for(TipoCobroEnum tipoCobro:cobrosFicticios){
			filtroTipoCobro.append(tipoCobro.getCodigo()).append(", ");
		}

		if(filtroTipoCobro.length()>0)
			filtroTipoCobro.delete(filtroTipoCobro.lastIndexOf(", "), filtroTipoCobro.length());

		sbEvento.append("END; ");

		queryDeleteEventosCobro = sbEvento.toString();
	}
	
	
	static{
		StringBuilder sbEvento= new StringBuilder();
		sbEvento.append(" BEGIN ");
		sbEvento.append("    UPDATE pa_evento");
		sbEvento.append("    SET");
		sbEvento.append("        es_activo = 0");
		sbEvento.append("    WHERE");
		sbEvento.append("        id IN ( ");
		sbEvento.append("			SELECT");
		sbEvento.append("    			ev.id");
		sbEvento.append("			FROM ");
		sbEvento.append("    			pa_evento ev");
		sbEvento.append("			INNER JOIN pa_operacion ope ON ev.id_operacion = ope.id");
		sbEvento.append("			INNER JOIN pa_planevento pev ON ev.id_plan_evento = pev.id");
		sbEvento.append("			INNER JOIN pa_plan_calendario_comision pcc ON pev.id = pcc.id");
		sbEvento.append("			INNER JOIN pa_plan_calculo_comisiones pccc ON pcc.id = pccc.id_plan_cal_comision");
		sbEvento.append("			LEFT JOIN pa_cobroevento ce ON ce.id_evento_asociado = ev.id");
		sbEvento.append("			LEFT JOIN pa_cobropuntual cp ON cp.id = ce.ID_COBRO");
		sbEvento.append("			WHERE");
		sbEvento.append("				ev.id_operacion = ?");
		sbEvento.append("				AND ev.fechafinvalidez_tipo > ?");
		sbEvento.append("				AND ope.disc_operacion = 'OP_FD'");
		sbEvento.append("				AND (ce.id_cobro is null OR cp.fecha_cobro >= ?)");
		sbEvento.append("				AND pccc.prepagable = 1");
		sbEvento.append("               AND EV.ID <> ? ");
		sbEvento.append("				);");
		sbEvento.append(" END; ");//ICO-67866 y ICO-68147 Cambio operador '>=' por '>' en fechafinvalidez_tipo. Añadidos LEFT JOIN de pa_cobroevento y pa_cobropuntual y comprobaciones de cobro en WHERE
		queryDeleteEventosComisionesPrepagablesCobro = sbEvento.toString();
	}	

	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void setFechaInterfazContable(Long idOperacion, Date fecha) throws Exception {

		Date fechaRegeneracion = null;
		Date fechaContabilidad = null;
		Date fechaHoy = null;
		String reenviar="", reenviarPer="";
		
		StringBuilder sb, sbUpdate=null, sbInsert=null;
		Query query, queryUpdate;
		
		try {
			sb = new StringBuilder("SELECT FECHA_REGENERACION, FECHA_CONTABILIDAD, to_date (to_char (SYSDATE,'dd-mm-rrrr')), REENVIAR, REENVIARPER FROM PA_INTERFAZ_CONTABLE_FECHA WHERE ID_OPERACION = ?");
			query = getEm().createNativeQuery(sb.toString());
			query.setParameter(1, idOperacion);

			List<Object[]> lista_fechas = (List<Object[]>)query.getResultList();
	
			// No existe registro de fechas contables para la operación en la tabla PA_INTERFAZ_CONTABLE_FECHA, se inserta
			if (lista_fechas == null || lista_fechas.size() == 0) {

				Date fechaParaInsertar = calculaFechaParaInsertar(idOperacion);
				if (fechaParaInsertar != null) {

					sbInsert = new StringBuilder(
							"INSERT INTO PA_INTERFAZ_CONTABLE_FECHA ");
					sbInsert.append(" (ID_OPERACION, FECHA_REGENERACION, REENVIAR, REENVIARPER, ENVIARBAJA, ");
					sbInsert.append(" FECHAALTAREGISTRO, FECHAMODIFICACIONREGISTRO, USUARIOALTAREGISTRO, USUARIOMODIFICACIONREGISTRO )");
					sbInsert.append(" VALUES (?, ?, 'NO', 'NO', 'NO', ");
					sbInsert.append(" SYSDATE, SYSDATE, 'ADMIN', 'ADMIN')");

					Query queryInsert = getEm().createNativeQuery(
							sbInsert.toString());
					queryInsert.setParameter(1, idOperacion);
					queryInsert.setParameter(2, fechaParaInsertar);

					queryInsert.executeUpdate();

					LOG.info("setFechaInterfazContable: insertamos nuevo registro para idOperacion="
									+ idOperacion);
				}
				return;
			}
			
			if (lista_fechas!= null && lista_fechas.size()>1) {
				LOG.error("Fallo en setFechaInterfazContable id: "+ idOperacion + " Registros de fechas contables DUPLICADOS ");
				return;
			}
			
			//Se ha obtenido un registro de fechas contables único
			Object[] fechas =  (Object[]) lista_fechas.get(0);
			fechaRegeneracion=FechaUtils.truncateDate((Date)fechas[0]);
			fechaContabilidad=FechaUtils.truncateDate((Date)fechas[1]);
			fechaHoy=FechaUtils.truncateDate((Date)fechas[2]);
			fecha=FechaUtils.truncateDate(fecha);
			reenviar=fechas[3].toString();
			reenviarPer=fechas[4].toString();

			//Existe el registro de la operacion en la tabla PA_INTERFAZ_CONTABLE_FECHA
			if(fechaRegeneracion != null) {
	
				//Si la fecha del cambio es posterior a la fecha de regeneración
				// - Si la operacion no tiene movimientos pendientes de enviar (cambios a futuro) se marca reenvío de periodificaciones a SI
				// para reenviar SÓLO los datos de periodificaciones
				// - Si la operación tiene cambios anteriores a la fecha de hoy se marca reenvío de movimientos y de periodificaciones a SI
				// para enviar movs y pers desde la fecha de regeneración
				
				//Si la fecha del cambio es posterior a la fecha de regeneración 
				// - Si la operación tiene su envío a contabilidad al día (fecha contab = fecha reg y reenviar =NO)
				//     - Si la fecha del cambio es posterior a hoy => solo se envían las periodificaciones
				//     - Si la fecha del cambio es anterior a hoy => se envían movimientos y periodificaciones
				
				// controlamos si está al día el registro para no cargar bbdd
				if (fechaRegeneracion.before(fecha) || fechaRegeneracion.equals(fecha)){
					if (fechaRegeneracion.equals(fechaContabilidad) && !reenviar.equals("SI")){
						if (fecha.equals(fechaRegeneracion)){
							
							sbUpdate = new StringBuilder("UPDATE PA_INTERFAZ_CONTABLE_FECHA SET REENVIAR = 'SI', REENVIARPER = 'SI', FECHAMODIFICACIONREGISTRO=SYSDATE, USUARIOMODIFICACIONREGISTRO='ADMIN' WHERE ID_OPERACION = ?");
							LOG.info("setFechaInterfazContable: fechaRegeneracion=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaRegeneracion)) + 
								" fechaContabilidad=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaContabilidad)) + 
								" fechaHoy=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaHoy)) + " reenviar=" + reenviar + " reenviarPer=" + reenviarPer +
								" ==> reenviar=SI, reenviarPER=SI" + ". idOperacion=" + idOperacion);		
							
							queryUpdate = getEm().createNativeQuery(sbUpdate.toString());
							queryUpdate.setParameter(1, idOperacion);
							queryUpdate.executeUpdate();
							
						}else if (fechaRegeneracion.before(fecha) && (fecha.before(fechaHoy) || fecha.equals(fechaHoy))){
						
							sbUpdate = new StringBuilder("UPDATE PA_INTERFAZ_CONTABLE_FECHA SET FECHA_REGENERACION = ?, REENVIAR = 'SI', REENVIARPER = 'SI', FECHAMODIFICACIONREGISTRO=SYSDATE, USUARIOMODIFICACIONREGISTRO='ADMIN' WHERE ID_OPERACION = ?");
							LOG.info("setFechaInterfazContable: fechaRegeneracion=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaRegeneracion)) + 
									" fechaContabilidad=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaContabilidad)) + 
									" fechaHoy=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaHoy)) + " reenviar=" + reenviar + " reenviarPer=" + reenviarPer +
									" ==> fechaRegeneracion=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fecha)) + ". idOperacion=" + idOperacion);
							
							queryUpdate = getEm().createNativeQuery(sbUpdate.toString());
							queryUpdate.setParameter(1, fecha);
							queryUpdate.setParameter(2, idOperacion);
							queryUpdate.executeUpdate();
							
						}else if (fechaHoy.before(fecha) && !reenviarPer.equals("SI")){
							sbUpdate = new StringBuilder("UPDATE PA_INTERFAZ_CONTABLE_FECHA SET REENVIARPER = 'SI', FECHAMODIFICACIONREGISTRO=SYSDATE, USUARIOMODIFICACIONREGISTRO='ADMIN' WHERE ID_OPERACION = ?");
							LOG.info("setFechaInterfazContable: fechaRegeneracion=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaRegeneracion)) + 
									" fechaContabilidad=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaContabilidad)) + 
									" fechaHoy=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaHoy)) + " reenviar=" + reenviar + " reenviarPer=" + reenviarPer +
									" ==> reenviarPER=SI" + ". idOperacion=" + idOperacion);	
							
							queryUpdate = getEm().createNativeQuery(sbUpdate.toString());
							queryUpdate.setParameter(1, idOperacion);
							queryUpdate.executeUpdate();
						}
						

					}
					else
						return;
				
				
				//Si la fecha del cambio es anterior a la de fecha de regeneración, se actualiza esta última con la fecha del cambio
				} else  if(fecha.before(fechaRegeneracion)) {
					sbUpdate = new StringBuilder("UPDATE PA_INTERFAZ_CONTABLE_FECHA SET FECHA_REGENERACION = ?, FECHAMODIFICACIONREGISTRO=SYSDATE, USUARIOMODIFICACIONREGISTRO='ADMIN' WHERE ID_OPERACION = ?");
					queryUpdate = getEm().createNativeQuery(sbUpdate.toString());
					queryUpdate.setParameter(1, fecha);
					queryUpdate.setParameter(2, idOperacion);
	
					queryUpdate.executeUpdate();
	
					LOG.info("setFechaInterfazContable: fechaRegeneracion=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaRegeneracion)) + 
						" fechaContabilidad=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaContabilidad)) + 
						" fechaHoy=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaHoy)) + " reenviar=" + reenviar + " reenviarPer=" + reenviarPer +
						" ==> fechaRegeneracion=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fecha)) + ". idOperacion=" + idOperacion);							
							

				// si la fecha del cambio es la misma que la fecha de regeneración y coincide con la fecha
				// de último envio a contabilidad marcamos el reenvío a SI
				// para que los datos del día no se den por ya enviados (ICO-11476)
				} /*else if (!fechaRegeneracion.after(fecha) && fechaRegeneracion.equals(fechaContabilidad) && !reenviar.equals("SI")) {
					sbUpdate = new StringBuilder("UPDATE PA_INTERFAZ_CONTABLE_FECHA SET REENVIAR= 'SI' WHERE ID_OPERACION = ?");
					queryUpdate = getEm().createNativeQuery(sbUpdate.toString());
					queryUpdate.setParameter(1, idOperacion);
	
					queryUpdate.executeUpdate();
				}
				
				} */
				
				else
					LOG.info("setFechaInterfazContable: fechaRegeneracion=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaRegeneracion)) + 
							" fechaContabilidad=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaContabilidad)) + 
							" fechaHoy=" + FechaUtils.formatDateConGuiones(FechaUtils.truncateDate(fechaHoy)) + " reenviar=" + reenviar + " reenviarPer=" + reenviarPer +
							" ==> Sin Cambios. idOperacion=" + idOperacion);
	
			}
			
			
			
		}
		catch(Exception e) {
			if (fecha!=null)
				LOG.error("Fallo en setFechaInterfazContable id: "+ idOperacion + " fecha " + FechaUtils.formatDateConGuiones(fecha));
			else
				LOG.error("Fallo en setFechaInterfazContable id: "+ idOperacion + " fecha nula");
			if (sbUpdate!=null)
				LOG.error("Fallo en setFechaInterfazContable query: "+ sbUpdate);
			if (sbInsert!=null)
				LOG.error("Fallo en setFechaInterfazContable query: "+ sbInsert);
		}
	}

	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	private Date calculaFechaParaInsertar(Long idOperacion){
		
		StringBuilder sb = new StringBuilder("SELECT TRUNC(MIN(FECHA_EVENTO)) FROM PA_OPERACION O INNER JOIN PA_EVENTO E ON O.ID=E.ID_OPERACION ");
		sb.append("WHERE DISCRIMINADOR<>1 AND O.ID=? ");
		
		Query query=getEm().createNativeQuery(sb.toString());
		query.setParameter(1, idOperacion);
		
		Date fechaParaInsertar=(Date)query.getSingleResult();
		
		return fechaParaInsertar;
	}
	
	public String buscarProducto(Long idOperacion) {
		Query query = getEm().createNativeQuery("select p.codigo from pa_operacion o, cuenta c, producto p where o.cuenta = c.id and c.producto = p.id and o.id = ?");
		query.setParameter(1, idOperacion);
		String codigoProducto = "";
		
		try {
			codigoProducto = (String)query.getSingleResult();
		}
		catch(Exception e) {
			
		}
		
		return codigoProducto;
	}

	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public HashMap<Date, List<Cobro>> recuperaCobrosParaDemoras(Date fechaEjecucion, Operacion operacion) throws SQLException, POJOValidationException{
		
		HashMap<Date, List<Cobro>> cobrosAux=new HashMap<Date, List<Cobro>>();
		Collection<Cobro> cobrosPuntuales = cobrosJDBC.searchCobrosPuntualesPorOperacion(operacion, FechaUtils.restaDia(fechaEjecucion), false);
		for(Cobro cobroPuntual : cobrosPuntuales){
			if(cobrosAux.get(FechaUtils.truncateDate(cobroPuntual.getFechaCobro())) == null)
				cobrosAux.put(FechaUtils.truncateDate(cobroPuntual.getFechaCobro()), new ArrayList<Cobro>());
			cobrosAux.get(FechaUtils.truncateDate(cobroPuntual.getFechaCobro())).add(cobroPuntual);
		}
		
		return cobrosAux;
		
	}
	
	@TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
	public void actualizarBasePDIS(Long idOperacion) {
		StringBuilder sQueryEventos = new StringBuilder("UPDATE pa_plan_calculo_intereses ");
		sQueryEventos.append(" SET basecalculo = ");
		sQueryEventos.append("   (SELECT basecalculo ");
		sQueryEventos.append(" FROM pa_planevento e INNER JOIN pa_plan_calculo_intereses c ");
		sQueryEventos.append("           ON c.id_plan_evento = e.ID ");
		sQueryEventos.append("     WHERE e.id_operacion = ? ");
		sQueryEventos.append("      AND e.disc_plan = 'PI_OPE' ");
		sQueryEventos.append("       AND e.activo = 1) ");
		sQueryEventos.append("  WHERE id_plan_evento IN ( ");
		sQueryEventos.append("   SELECT e.ID ");
		sQueryEventos.append("     FROM pa_planevento e INNER JOIN pa_plan_calculo_intereses c ");
		sQueryEventos.append("          ON c.id_plan_evento = e.ID ");
		sQueryEventos.append("    WHERE e.id_operacion = ? ");
		sQueryEventos.append("      AND e.disc_plan = 'PI_DIS' ");
		sQueryEventos.append("      AND e.activo = 1) ");

		Query query = getEm().createNativeQuery(sQueryEventos.toString());

		query.setParameter(1, idOperacion);
		query.setParameter(2, idOperacion);

		query.executeUpdate();
	}
	
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public void loadPeriodosCarencia(Long idOperacion, EventosOperacion eventosOperacion){
		
		try{
			List<List<Date>> periodosCarencia = operacionActivoDAO.getPeriodosCarencia(idOperacion, eventosOperacion);
			
			eventosOperacion.setPeriodosCarencia(periodosCarencia);
			
		} catch (SQLException e) {
			eventosOperacion.setPeriodosCarencia(null);
			LOG.error(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getDisposicionesAltaReintegro (Operacion operacion, List<Evento> eventos, Date fecha, EventosOperacion eventosOperacion) {

		Long idOperacion = operacion.getId();

		StringBuilder consultaSQL = new StringBuilder("SELECT e.id FROM pa_evento e");
		consultaSQL.append(" WHERE e.id_operacion = ? ");
		consultaSQL.append(" and e.es_activo = 1 and e.DISCRIMINADOR in (2, 3, 4, 5)" );
		consultaSQL.append(" and e.id_evento_total is null");
		consultaSQL.append(" order by e.fecha_evento");

		Query query = getEm().createNativeQuery(consultaSQL.toString());

		query.setParameter(1,  idOperacion);
		
		List<BigDecimal> idsDisposiciones = query.getResultList();

		for(BigDecimal idDisposicion : idsDisposiciones) {
			Evento evento = loadEvento(idDisposicion.longValue(), eventosOperacion, operacion, fecha);
			evento.setOperacion(operacion);
			if(!eventos.contains(evento)){
				eventos.add(evento);
			}
		}

		return eventos;
	}

	//ICO-59393 Método que obtiene query para obtener ids eventos comisiones no disponibilidad	
	public Query getQueryComisionNoDisp(Long idOp) {
		
		  StringBuilder consultaSQL = new StringBuilder(" SELECT id ");
		  consultaSQL.append("  FROM pa_evento e ");
		  consultaSQL.append("  WHERE id_operacion = ? ");
		  consultaSQL.append("        AND es_activo = ? ");
		  consultaSQL.append("        AND discriminador = ? ");
	  
		  Query query = getEm().createNativeQuery(consultaSQL.toString());
	  
		  query.setParameter(1, idOp);
		  query.setParameter(2, true);
		  query.setParameter(3, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD.getDiscriminador());
		  
		  return (query);	  	  
	}
	
	//ICO-60273
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Date getFechaInicioPrimerEventoSaldosAmort(Long idOperacion) {
		try {
			Query query = getEm().createNativeQuery(" select min(pso.fechasaldo) from pa_saldos_ope pso " + 
					" where pso.id_operacion=( select id from pa_operacion po " + 
					" inner join pa_relacion_host rh on rh.id_operacion=po.id " + 
					" where id_operacion=? and pso.cpv>0) order by pso.fechasaldo asc ");
			
			query.setParameter(1, idOperacion);

			return FechaUtils.truncateDate((Date)query.getSingleResult());

		} catch(NoResultException e) {
			return null;
		}
	}
	
	//ICO-58721 Método que comprueba si al operacion tiene plan comision disponibilidad activo
    private boolean existePlanComisionDisponibilidad(Operacion operacion)
    {
        Set<PlanCalendarioComision> pcc = operacion.getPlanesCalendarioComision();
        Iterator<PlanCalendarioComision> it = pcc.iterator();
                       
        boolean existe = false;
        while (it.hasNext() && !existe) 
        {
            PlanCalendarioComision planEvento = it.next();
            
            if(planEvento instanceof PlanCalendarioComisionPeriodico && 
			((PlanCalendarioComisionPeriodico)planEvento).getPlanComision() instanceof PlanComisionDisponibilidadImp) {
            	existe = true; 
            }	            
        }   
        return existe; 
    } 
    
	//ICO-58721 Método que obtiene query para obtener ids eventos comisiones no disponibilidad
    //cuyo plan de evento no esté activo y que no exista otro plan de comisión por importe no dispuesto activo anterior a la fecha de la comisión
	public Query getQueryComisionNoDispBajaPlan(Operacion operacion) {
		
		  StringBuilder consultaSQL = new StringBuilder(" SELECT id ");
		  consultaSQL.append("  FROM pa_evento e ");
		  consultaSQL.append("  WHERE e.id_operacion = ? ");
		  consultaSQL.append("        AND e.es_activo = ? ");
		  consultaSQL.append("        AND e.discriminador = ? ");
		  consultaSQL.append("        AND NOT EXISTS (SELECT * FROM PA_PLANEVENTO pe1 WHERE pe1.ID = e.id_plan_evento AND pe1.activo = 1 AND pe1.disc_plan = 'PCC_VP') ");
		  if(existePlanComisionDisponibilidad(operacion)) {
			  consultaSQL.append("  AND EXISTS (SELECT * FROM PA_PLANEVENTO pe2 ");
			  consultaSQL.append("  INNER JOIN pa_plan_calculo_comisiones pcc ON pcc.id_plan_cal_comision = pe2.id ");
			  consultaSQL.append("  INNER JOIN pa_plan_calendario_comision pcalc ON pcc.id_plan_cal_comision = pcalc.id ");
			  consultaSQL.append("  WHERE pe2.id_operacion = e.id_operacion AND pe2.activo = 1 AND pe2.disc_plan = 'PCC_VP' ");
			  consultaSQL.append("  AND pcc.discriminador = 'PC_DIS' AND pcalc.fecha_primer_venc >= e.fecha_evento) ");
		  }
		  
		  Query query = getEm().createNativeQuery(consultaSQL.toString());
	  
		  query.setParameter(1, operacion.getId());
		  query.setParameter(2, true);
		  query.setParameter(3, SubtipoEventoEnum.LIQUIDACION_COMISION_DISPONIBILIDAD.getDiscriminador());
		  
		  return (query);	  	  
	}
	
	//INI - ICO-67866 - 05/10/2021
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public void obtenerEventosPrepagables(Operacion operacion, Date fecha, EventosOperacion eventosOperacion, List<Evento> eventos) {
		
		List<Date> fechasEventosPrepagables = new ArrayList<Date>();
		List<Long> idsEventosPrepagables = new ArrayList<Long>();
		
		String sqlString = "SELECT ev.fecha_inicio, ev.id" +
				" FROM pa_evento ev" +
				" INNER JOIN pa_operacion ope ON ev.id_operacion = ope.id" +
				" INNER JOIN pa_planevento pev ON ev.id_plan_evento = pev.id" +
				" INNER JOIN pa_plan_calendario_comision pcc ON pev.id = pcc.id" +
				" INNER JOIN pa_plan_calculo_comisiones pccc ON pcc.id = pccc.id_plan_cal_comision" +
				" INNER JOIN pa_cobroevento ce on ce.ID_EVENTO_ASOCIADO=ev.id" +
				" INNER JOIN pa_cobropuntual cp on cp.id = ce.id_cobro" +
				" WHERE" +
				"    ev.id_operacion = ?" +
				"    AND ev.fechafinvalidez_tipo >= ?" +
				"	 AND ope.disc_operacion = 'OP_FD'" +
				"    AND cp.fecha_cobro < ?" +
				"    AND pccc.prepagable = 1";
		
		Query query = getEm().createNativeQuery(sqlString);
		query.setParameter(1, operacion.getId());
		query.setParameter(2, fecha);
		query.setParameter(3, fecha);
		
		List results = query.getResultList();
		for(Object result : results) {
			fechasEventosPrepagables.add((Date)Array.get(result, 0));
			long l = ((Number) Array.get(result, 1)).longValue();
			idsEventosPrepagables.add(l);
		}
		
		eventosOperacion.setFechasEventosPrepagables(fechasEventosPrepagables);
		
		for(Long idEvento : idsEventosPrepagables) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			//evento.setOperacion(operacion);
			eventos.add(evento);
		}
	}
	//FIN - ICO-67866 - 05/10/2021
	
	public void obtenerFechaFinAmortizacionCaratula(Long idOperacion, EventosOperacion eventosOperacion) {
		
		Date fechaFin = null;
		
		StringBuilder consultaSQL = new StringBuilder("SELECT pao.fechavencimiento FROM pa_operacion o ");
		consultaSQL.append("INNER JOIN pa_planevento pe ON o.id=pe.id_operacion ");
		consultaSQL.append("INNER JOIN pa_plan_amortizacion_operacion pao ON pao.id=pe.id ");
		consultaSQL.append("WHERE o.id= ? ");
		consultaSQL.append("AND pe.activo=1");
		
		Query query = getEm().createNativeQuery(consultaSQL.toString());
		query.setParameter(1, idOperacion);
		
		fechaFin = FechaUtils.truncateDate((Date)query.getSingleResult());
		
		eventosOperacion.setFechaFinAmortizacionCaratula(fechaFin);
	}
	
	
	//INI ICO-68057
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> getEventosDevolucionFactura(Operacion operacion, Date fecha, EventosOperacion eventosOperacion, DisposicionOperacion disposicion) {

		String consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.class in (?)  and TRUNC(e.fechaEvento) < ?  " + //+1 de ICO-51599 
				" order by e.id";

		List<Long> idsEventos = new ArrayList<Long>();
		idsEventos = getQueryEventosDevolucionFactura(consultaSQL, disposicion, operacion);
		

		List<Evento> eventos = new ArrayList<Evento>();

		for(Long idEvento : idsEventos) {
			Evento evento = loadEventoDevolucionFactura(idEvento, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}
		if (!eventos.isEmpty()) {
			
			
			for (int i = 0; i < eventos
					.size(); i++) {
				eventosOperacion.addEventoDevolucionFactura(
						eventos.get(
								i));
			}
		}
		return eventos;
	}
	
	public List<Long> getQueryEventosDevolucionFactura(String consultaSQL, DisposicionOperacion disposicion, Operacion operacion){
		
		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3, SubtipoEventoEnum.AMORTIZACION_ANTICIPADA_DEVOLUCION_FACTURA.getDiscriminador());
		
		query.setParameter(4,  disposicion.getFechaInicio());

		return query.getResultList();
	}
	
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public Evento loadEventoDevolucionFactura (Long idEvento,Operacion operacion, Date fechaEjecucion) throws JDBCException {
		
		Query query = getEm().createQuery("SELECT amr FROM AmortizacionManualImp amr" +
					" LEFT JOIN FETCH amr.planesEventosAsociados pe " +
					" WHERE amr.id = ?  and amr.esEstadoActivo=?");
		query.setParameter(1, idEvento);
		query.setParameter(2, true);
		
		return (Evento) query.getSingleResult();

	}
	//FIN ICO-68057
	
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public List<Evento> searchEventosCobrablesByIdOperacion(long idOperacion, Date fechaDesde, String aplicacion ) {
		List<Evento> eventoList;
		StringBuilder hql = new StringBuilder();
		hql.append(" from " +
				EventoImp.class.getName()+ " e " +
				" left join fetch e.operacion " +
				" left join fetch e.operacion.cuentaPA " +
				" left join fetch e.eventosDependientes " );


				if(aplicacion.equals(AplicacionCobrosEnum.APLICACION_CUOTA_CLIENTE.getCodigo()) || aplicacion.equals(AplicacionCobrosEnum.APLICACION_SUBSIDIO_AUTONOMICO.getCodigo()) || aplicacion.equals(AplicacionCobrosEnum.APLICACION_SUBSIDIO_MINISTERIO.getCodigo())) {
					hql.append(" where e.esEstadoActivo = 1 " +
							" and e.operacion.id = ? " +
							" and e.fechaEvento > ? and e.discriminador in (13)"
//							" and ((e.fechaVencimientoAjustada is not null and e.fechaVencimientoAjustada < e.fechaEvento and e.fechaVencimientoAjustada < ?) " +
//							"   or (e.fechaEvento < ?))" //ICO-67866
					);
				}
				if(aplicacion.equals(AplicacionCobrosEnum.APLICACION_SUBSIDIO_AUTONOMICO.getCodigo())) {
					hql.append(" and e.eventoTotal is null and e.class in (63)"); 		
				}
				if(aplicacion.equals(AplicacionCobrosEnum.APLICACION_SUBSIDIO_MINISTERIO.getCodigo())) {
					hql.append(" and e.eventoTotal is null and e.class in (53, 54, 55)"); 		
				}
				
				hql.append(" order by e.fechaEvento asc"); 
		Query query = getEm().createQuery(hql.toString());
		query.setParameter(1, idOperacion);
		query.setParameter(2, fechaDesde);

		eventoList = query.getResultList();

		return eventoList;

	}
	
	
	@SuppressWarnings("unchecked")
	@TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
	public void getSubsidiosIgualesAltaCobro(Operacion operacion, Date fecha, EventosOperacion eventosOperacion, List<Evento> eventos) {

		String consultaSQL = "";
		
		consultaSQL = "SELECT e.id FROM EventoImp e"+
				" WHERE e.operacion.id = ? "+
				" and e.esEstadoActivo = ?"+
				" and e.class in (?)  and trunc(e.fechaEvento) = ?  " + // ICO-63275 - Se añade AAD
				" order by e.id";

		Query query = getEm().createQuery(consultaSQL);

		query.setParameter(1,  operacion.getId());
		query.setParameter(2,  true);
		query.setParameter(3,  SubtipoEventoEnum.SUBSIDIO_CUOTA.getDiscriminador());
		query.setParameter(4,  fecha);

		List<Long> idsEventos = query.getResultList();


		for(Long idEvento : idsEventos) {
			Evento evento = loadEvento(idEvento, eventosOperacion, operacion,fecha);
			evento.setOperacion(operacion);
			eventos.add(evento);
		}

	}
	

}